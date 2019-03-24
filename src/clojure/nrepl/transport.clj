(ns nrepl.transport
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [send])
  (:require
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [nrepl.bencode :as bencode]
   [clojure.edn :as edn]
   [nrepl.misc :refer [uuid]]
   nrepl.version)
  (:import
   clojure.lang.RT
   [java.io EOFException PushbackInputStream PushbackReader]
   [java.net Socket SocketException]
   [java.util.concurrent BlockingQueue LinkedBlockingQueue SynchronousQueue TimeUnit]))

(defprotocol Transport
  "Defines the interface for a wire protocol implementation for use
   with nREPL."
  (recv [this] [this timeout]
    "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
  (send [this msg] "Sends msg. Implementations should return the transport."))

;; adapted from clojure.walk to support namespaced keywords
(defn- stringify-key
  [k]
  (cond
    (and (keyword? k) (namespace k)) (str (namespace k) "/" (name k))
    (keyword? k) (name k)
    :else k))

(defn- stringify-keys
  [m]
  (let [f (fn [[k v]] [(stringify-key k) v])]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(deftype FnTransport [recv-fn send-fn close]
  Transport
  ;; TODO: this keywordization/stringification has no business being in FnTransport
  (send [this msg] (-> msg stringify-keys send-fn) this)
  (recv [this] (.recv this Long/MAX_VALUE))
  (recv [this timeout] (walk/keywordize-keys (recv-fn timeout)))
  java.io.Closeable
  (close [this] (close)))

(defn fn-transport
  "Returns a Transport implementation that delegates its functionality
   to the 2 or 3 functions provided."
  ([transport-read write] (fn-transport transport-read write nil))
  ([transport-read write close]
   (let [read-queue (SynchronousQueue.)
         msg-pump (future (try
                            (while true
                              (.put read-queue (transport-read)))
                            (catch Throwable t
                              (.put read-queue t))))]
     (FnTransport.
      (let [failure (atom nil)]
        #(if @failure
           (throw @failure)
           (let [msg (.poll read-queue % TimeUnit/MILLISECONDS)]
             (if (instance? Throwable msg)
               (do (reset! failure msg) (throw msg))
               msg))))
      write
      (fn [] (close) (future-cancel msg-pump))))))

(defmulti #^{:private true} <bytes class)

(defmethod <bytes :default
  [input]
  input)

(defmethod <bytes (RT/classForName "[B")
  [#^"[B" input]
  (String. input "UTF-8"))

(defmethod <bytes clojure.lang.IPersistentVector
  [input]
  (vec (map <bytes input)))

(defmethod <bytes clojure.lang.IPersistentMap
  [input]
  (->> input
       (into {} (map (fn [[k v]]
                       [k (<bytes v)])))))

(defmacro ^{:private true} rethrow-on-disconnection
  [^Socket s & body]
  `(try
     ~@body
     (catch RuntimeException e#
       (if (= "EOF while reading" (.getMessage e#))
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch EOFException e#
       (if (= "Invalid netstring. Unexpected end of input." (.getMessage e#))
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch Throwable e#
       (if (and ~s (not (.isConnected ~s)))
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))))

(defn bencode
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using bencode."
  ([^Socket s] (bencode s s s))
  ([in out & [^Socket s]]
   (let [in (PushbackInputStream. (io/input-stream in))
         out (io/output-stream out)]
     (fn-transport
      #(let [payload (rethrow-on-disconnection s (bencode/read-bencode in))
             unencoded (<bytes (payload "-unencoded"))
             to-decode (apply dissoc payload "-unencoded" unencoded)]
         (merge (dissoc payload "-unencoded")
                (when unencoded {"-unencoded" unencoded})
                (<bytes to-decode)))
      #(rethrow-on-disconnection s
                                 (locking out
                                   (doto out
                                     (bencode/write-bencode %)
                                     .flush)))
      (fn []
        (if s
          (.close s)
          (do
            (.close in)
            (.close out))))))))

(defn stringify-everything
  [m]
  (walk/postwalk (fn [v] (if (keyword? v)
                           (let [nspace (namespace v)
                                 name      (name v)]
                             (str (when nspace (str nspace "/")) name))
                           v))
                 m))

(defn nrepl+edn
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using EDN."
  ([^Socket s] (nrepl+edn s s s))
  ([in out & [^Socket s]]
   (let [in (java.io.PushbackReader. (io/reader in))
         out (io/writer out)]
     (fn-transport
      #(rethrow-on-disconnection s (stringify-everything (edn/read in)))
      #(rethrow-on-disconnection s
                                 (locking out
                                   (binding [*print-readably* true]
                                     (let [payload (pr-str (stringify-everything %))]
                                       (doto out
                                         (.write payload)
                                         (.flush))))))
      (fn []
        (if s
          (.close s)
          (do
            (.close in)
            (.close out))))))))

(defn tty
  "Returns a Transport implementation suitable for serving an nREPL backend
   via simple in/out readers, as with a tty or telnet connection."
  ([^Socket s] (tty s s s))
  ([in out & [^Socket s]]
   (let [r (PushbackReader. (io/reader in))
         w (io/writer out)
         cns (atom "user")
         prompt (fn [newline?]
                  (when newline? (.write w (int \newline)))
                  (.write w (str @cns "=> ")))
         session-id (atom nil)
         read-msg #(let [code (read r)]
                     (merge {:op "eval" :code [code] :ns @cns :id (str "eval" (uuid))}
                            (when @session-id {:session @session-id})))
         read-seq (atom (cons {:op "clone"} (repeatedly read-msg)))
         write (fn [{:strs [out err value status ns new-session id] :as msg}]
                 (when new-session (reset! session-id new-session))
                 (when ns (reset! cns ns))
                 (doseq [^String x [out err value] :when x]
                   (.write w x))
                 (when (and (= status #{:done}) id (.startsWith ^String id "eval"))
                   (prompt true))
                 (.flush w))
         read #(let [head (promise)]
                 (swap! read-seq (fn [s]
                                   (deliver head (first s))
                                   (rest s)))
                 @head)]
     (fn-transport read write
                   (when s
                     (swap! read-seq (partial cons {:session @session-id :op "close"}))
                     #(.close s))))))

(defn tty-greeting
  "A greeting fn usable with `nrepl.server/start-server`,
   meant to be used in conjunction with Transports returned by the
   `tty` function.

   Usually, Clojure-aware client-side tooling would provide this upon connecting
   to the server, but telnet et al. isn't that."
  [transport]
  (send transport {:out (str ";; nREPL " (:version-string nrepl.version/version)
                             \newline
                             ";; Clojure " (clojure-version)
                             \newline
                             "user=> ")}))

(defmulti uri-scheme
  "Return the uri scheme associated with a transport var."
  identity)

(defmethod uri-scheme #'bencode [_] "nrepl")

(defmethod uri-scheme #'tty [_] "telnet")

(defmethod uri-scheme :default
  [transport]
  (printf "WARNING: No uri scheme associated with transport %s\n" transport)
  "unknown")

(deftype QueueTransport [^BlockingQueue in ^BlockingQueue out]
  nrepl.transport.Transport
  (send [this msg] (.put out msg) this)
  (recv [this] (.take in))
  (recv [this timeout] (.poll in timeout TimeUnit/MILLISECONDS)))

(defn piped-transports
  "Returns a pair of Transports that read from and write to each other."
  []
  (let [a (LinkedBlockingQueue.)
        b (LinkedBlockingQueue.)]
    [(QueueTransport. a b) (QueueTransport. b a)]))
