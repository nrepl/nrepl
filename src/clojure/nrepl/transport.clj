(ns nrepl.transport
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [send])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [nrepl.bencode :as bencode]
   [nrepl.misc :refer [response-for uuid]]
   [nrepl.socket :as socket]
   [nrepl.util.threading :as threading]
   nrepl.version)
  (:import
   (clojure.lang RT
                 LineNumberingPushbackReader)
   (java.io ByteArrayOutputStream
            Closeable
            EOFException
            Flushable
            PushbackInputStream)
   [java.net SocketException]
   [java.nio.channels ClosedChannelException]
   [java.util.concurrent BlockingQueue LinkedBlockingQueue Semaphore SynchronousQueue TimeUnit]))

(defprotocol Transport
  "Defines the interface for a wire protocol implementation for use
   with nREPL."
  (recv [this] [this timeout]
    "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
  (send [this msg] "Sends msg. Implementations should return the transport."))

(deftype FnTransport [recv-fn send-fn close]
  Transport
  (send [this msg] (send-fn msg) this)
  (recv [this] (.recv this Long/MAX_VALUE))
  (recv [_this timeout] (recv-fn timeout))
  java.io.Closeable
  (close [_this] (close)))

(defn fn-transport
  "Returns a Transport implementation that delegates its functionality
   to the 2 or 3 functions provided."
  ([transport-read write] (fn-transport transport-read write nil))
  ([transport-read write close]
   (let [read-queue (SynchronousQueue.)
         fut (threading/run-with @threading/transport-executor
               (while (not (Thread/interrupted))
                 (try
                   (.put read-queue (transport-read))
                   (catch InterruptedException _
                     ;; Interrupted flag will end the loop.
                     (.put read-queue nil))
                   (catch Throwable t
                     (.put read-queue t)
                     (.interrupt (Thread/currentThread))))))]
     (FnTransport.
      (let [failure (atom nil)]
        #(if @failure
           (throw @failure)
           (let [msg (.poll read-queue % TimeUnit/MILLISECONDS)]
             (if (instance? Throwable msg)
               (do (reset! failure msg)
                   (.cancel fut true)
                   (throw msg))
               msg))))
      write
      (fn [] (close) (.cancel fut true))))))

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
       (map (fn [[k v]] [k (<bytes v)]))
       (into {})))

(defmacro ^{:private true} rethrow-on-disconnection
  [s & body]
  `(try
     ~@body
     (catch ClosedChannelException e#
       (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server")))
     (catch RuntimeException e#
       (if (= "EOF while reading" (.getMessage e#))
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch EOFException e#
       (if (= "Invalid netstring. Unexpected end of input." (.getMessage e#))
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch Throwable e#
       (if (let [s# ~s]
             (and (satisfies? socket/Connectable s#) (not (socket/is-connected? s#))))
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))))

(defn ^{:private true} safe-write-bencode
  "Similar to `bencode/write-bencode`, except it will only writes to the output
   stream if the whole `thing` is writable. In practice, it avoids sending partial
    messages down the transport, which is almost always bad news for the client.

   This will still throw an exception if called with something unencodable."
  [output thing]
  (let [buffer (ByteArrayOutputStream.)]
    (bencode/write-bencode buffer thing)
    (socket/write output (.toByteArray buffer))))

(defn bencode
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using bencode."
  ([s] (bencode s s s))
  ([in out & [s]]
   (let [in (PushbackInputStream. (socket/buffered-input in))
         out (socket/buffered-output out)]
     (fn-transport
      #(let [payload (rethrow-on-disconnection s (bencode/read-nrepl-message in))
             unencoded (<bytes (payload "-unencoded"))
             to-decode (apply dissoc payload "-unencoded" unencoded)]
         (walk/keywordize-keys (merge (dissoc payload "-unencoded")
                                      (when unencoded {"-unencoded" unencoded})
                                      (<bytes to-decode))))
      #(rethrow-on-disconnection s
                                 (locking out
                                   (safe-write-bencode out %)
                                   (.flush ^Flushable out)))
      (fn []
        (if s
          (.close ^Closeable s)
          (do
            (.close ^Closeable in)
            (.close ^Closeable out))))))))

(defn edn
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using EDN."
  {:added "0.7"}
  ([s] (edn s s s))
  ([in out & [s]]
   (let [in (java.io.PushbackReader. (io/reader in))
         out (io/writer out)]
     (fn-transport
      #(rethrow-on-disconnection s (edn/read in))
      #(rethrow-on-disconnection s
                                 (locking out
                                   ;; TODO: The transport doesn't seem to work
                                   ;; without these bindings. Worth investigating
                                   ;; why
                                   (binding [*print-readably* true
                                             *print-length*   nil
                                             *print-level*    nil]
                                     (doto out
                                       (.write (str %))
                                       (.flush)))))
      (fn []
        (if s
          (.close ^Closeable s)
          (do
            (.close in)
            (.close out))))))))

(def clojure<1-10 (not (resolve 'read+string)))

(defmacro read-form
  "Read a form from `in` stream."
  [in]
  (if clojure<1-10
    ;; Remains broken - remove when support for 1.8 & 1.9 is dropped
    `[(read {:read-cond :allow} ~in)]
    `(let [dummy-resolver# (reify clojure.lang.LispReader$Resolver
                             (currentNS    [_]             '_unused-ns)
                             (resolveAlias [_ _alias-sym#] '_unused-ns)
                             (resolveClass [_ _class-sym#] '_unused-class)
                             (resolveVar   [_ _var-sym#]   '_unused-var))
           [_forms# code-string#]
           (binding [*reader-resolver* dummy-resolver#]
             (read+string {:read-cond :preserve} ~in))]
       code-string#)))

(defn tty
  "Returns a Transport implementation suitable for serving an nREPL backend
   via simple in/out readers, as with a tty or telnet connection."
  ([s] (tty s s s))
  ([in out & [^Closeable s]]
   (let [r (LineNumberingPushbackReader. (io/reader in))
         w (io/writer out)
         cns (atom "user")
         prompt (fn [newline?]
                  (when newline? (.write w (int \newline)))
                  (.write w (str @cns "=> ")))
         read-sync (Semaphore. 1)
         read-id (atom nil)
         session-id (atom nil)
         read-msg #(let [id (str "eval" (uuid))
                         code (read-form r)]
                     (.acquire read-sync)
                     (reset! read-id id)
                     (merge {:op "eval" :code code :ns @cns :id id}
                            (when @session-id {:session @session-id})))
         read-seq (atom (cons {:op "clone"} (repeatedly read-msg)))
         write (fn [{:keys [out err value status ns new-session id]}]
                 (when new-session (reset! session-id new-session))
                 (when ns (reset! cns ns))
                 (when (and (some #{:done "done"} status)
                            (= id @read-id))
                   (.release read-sync))
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

(defmethod uri-scheme #'edn [_] "nrepl+edn")

(defmethod uri-scheme :default
  [transport]
  (printf "WARNING: No uri scheme associated with transport %s\n" transport)
  "unknown")

(deftype QueueTransport [^BlockingQueue in ^BlockingQueue out]
  nrepl.transport.Transport
  (send [this msg] (.put out msg) this)
  (recv [_this] (.take in))
  (recv [_this timeout] (.poll in timeout TimeUnit/MILLISECONDS)))

(defn piped-transports
  "Returns a pair of Transports that read from and write to each other."
  []
  (let [a (LinkedBlockingQueue.)
        b (LinkedBlockingQueue.)]
    [(QueueTransport. a b) (QueueTransport. b a)]))

(defn respond-to
  "Send a response for `msg` with `response-data` using message's transport."
  [msg & response-data]
  (send (:transport msg) (apply response-for msg response-data)))

(defmacro safe-handle
  "When given a message and a number of <op handler> pairs, invoke the handler
  with the op that matches message's op. If an exception is raised during
  handling, send an automatic error response through the message's transport
  with `:<op>-error` status. Special keyword `:else` can be used for an op to
  define a catch-all handler. Handlers should functions of 1 argument `msg`."
  {:style/indent 1}
  [msg & body] ;; `body` is used, otherwise CIDER acts up and misindents
  (assert (even? (count body)))
  (let [msg-sym (gensym "msg")
        op-sym (gensym "op")]
    `(let [~msg-sym ~msg
           ~op-sym (:op ~msg-sym)]
       (cond ~@(mapcat (fn [[op handler]]
                         (if (= op :else)
                           [:else `(~handler ~msg-sym)]
                           [`(= ~op-sym ~op)
                            `(try (respond-to ~msg-sym (~handler ~msg-sym))
                                  (catch Exception e#
                                    (respond-to ~msg-sym
                                                :status #{~(keyword (str op "-error")) :error :done}
                                                :message (.getMessage e#))))]))
                       (partition 2 body))))))
