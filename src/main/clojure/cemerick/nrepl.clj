(ns cemerick.nrepl
  (:require clojure.main)
  (:import (java.net ServerSocket)
    (java.io Reader InputStreamReader BufferedReader PushbackReader StringReader
      Writer OutputStreamWriter BufferedWriter PrintWriter StringWriter
      IOException)
    (java.util.concurrent Callable Future ExecutorService Executors TimeUnit
      ThreadFactory
      CancellationException ExecutionException TimeoutException)))

(def *print-stack-trace-on-error* false)

(try
  (try
    (require '[clojure.pprint :as pprint]) ; clojure 1.2.0+
    (catch Exception e
      ; clojure 1.0.0+ w/ contrib
      (require '[clojure.contrib.pprint :as pprint])))
  ; clojure 1.1.0 requires this eval, throws exception not finding pprint ns
  ; I think 1.1.0 was resolving vars in the reader instead of the compiler?
  (eval '(defn- pretty-print? [] pprint/*print-pretty*))
  (eval '(def pprint pprint/pprint))
  (catch Exception e
    ; no contrib available, fall back to prn
    (def pprint prn)
    (defn- pretty-print? [] false)))

(def #^ExecutorService executor (Executors/newCachedThreadPool
                                  (proxy [ThreadFactory] []
                                    (newThread [#^Runnable r]
                                      (doto (Thread. r)
                                        (.setDaemon true))))))

(def #^{:private true
        :doc "A map whose values are the Futures associated with client-requested evaluations,
              keyed by the evaluations' messages' IDs."}
       repl-futures (atom {}))

(defn get-all-msg-ids
  []
  (keys @repl-futures))

(defn interrupt
  [msg-id]
  (when-let [#^Future f (@repl-futures msg-id)]
    (.cancel f true)))

(defn- submit
  [#^Callable function]
  (.submit executor function))

(defn- get-root-cause [throwable]
  (loop [#^Throwable cause throwable]
    (if-let [cause (.getCause cause)]
      (recur cause)
      cause)))

(defn- submit-looping
  ([function]
    (submit-looping function (fn [#^java.lang.Throwable cause]
                               (when-not (or (instance? IOException cause)
                                           (instance? java.lang.InterruptedException cause)
                                           (instance? java.nio.channels.ClosedByInterruptException cause))
                                 ;(.printStackTrace cause)
                                 (pr-str "submit-looping: exception occured: " cause)))))
  ([function ex-fn]
    (submit (fn []
              (try
                (function)
                (recur)
                (catch Exception ex
                  (ex-fn (get-root-cause ex))))))))

(def version
  (when-let [in (-> submit class (.getResourceAsStream "/cemerick/nrepl/version.txt"))]
    (let [reader (-> in (InputStreamReader. "UTF-8") BufferedReader.)
          string (.readLine reader)]
      (.close reader)
      (->> (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)" string)
        rest
        (zipmap [:major :minor :incremental :qualifier])))))

;Message format:
;<integer>
;<EOL>
;(<string: key>
; <EOL>
; (<string: value> | <number: value>)
; <EOL>)+
;The initial integer specifies how many k/v pairs are in the next message.
;
;Not simply printing and reading maps because the client
;may not be clojure: e.g. whatever vimclojure might use to
;write/parse messages, a python/ruby/whatever client, etc.
(defn- write-message
  "Writes the given message to the writer. Returns the :id of the message."
  [#^Writer out msg]
  (locking out
    (binding [*out* out]
      (prn (count msg))
      (doseq [[k v] msg]
        (if (string? k)
          (prn k)
          (prn (name k)))
        (prn v))
      (flush)))
  (:id msg))

(defn- read-message
  "Returns the next message from the given PushbackReader."
  [#^PushbackReader in]
  (locking in
    (binding [*in* in]
      (let [msg-size (read)]
        (->> (repeatedly read)
          (take (* 2 msg-size))
          (partition 2)
          (map #(vector (-> % first keyword) (second %)))
          (into {}))))))

(defn- init-client-state
  "Returns a map containing the 'baseline' client state of the current thread; everything
   that with-bindings binds, except for the prior result values, *e, and *ns*."
  []
  {:warn-on-reflection *warn-on-reflection*, :math-context *math-context*,
   :print-meta *print-meta*, :print-length *print-length*,
   :print-level *print-level*, :compile-path *compile-path*
   :command-line-args *command-line-args*
   :ns (create-ns 'user)})

(defmacro #^{:private true} set!-many
  [& body]
  (let [pairs (partition 2 body)]
    `(do ~@(for [[var value] pairs] (list 'set! var value)))))

(defn- handle-request
  [client-state-atom {:keys [code]}]
  (let [{:keys [value-3 value-2 value-1 last-exception ns warn-on-reflection
                math-context print-meta print-length print-level compile-path
                command-line-args]} @client-state-atom
        ; it seems like there's more value in combining *out* and *err*
        ; (thereby preserving the interleaved nature of that output, as typically rendered)
        ; than there is in separating them for the client
        ; (which could never recombine them properly, unless we timestamp each line or something)
        out (StringWriter.)
        out-pw (PrintWriter. out)
        return (atom nil)
        repl-init (fn []
                    (in-ns (.name ns))
                    (set!-many
                      *3 value-3
                      *2 value-2
                      *1 value-1
                      *e last-exception
                      *warn-on-reflection* warn-on-reflection
                      *math-context* math-context
                      *print-meta* print-meta
                      *print-length* print-length
                      *print-level* print-level
                      *compile-path* compile-path
                      *command-line-args* command-line-args))]
    (try
      (binding [*in* (clojure.lang.LineNumberingPushbackReader. (StringReader. code))
                *out* out-pw
                *err* out-pw]
        (clojure.main/repl
          :init repl-init
          :read (fn [prompt exit] (read *in* false exit))
          :caught (fn [#^Throwable e]
                    (swap! client-state-atom assoc :last-exception e)
                    (reset! return ["error" nil])
                    (if *print-stack-trace-on-error*
                      (.printStackTrace e *out*)
                      (prn (clojure.main/repl-exception e)))
                    (flush))
          :prompt (fn [])
          :need-prompt (constantly false)
          :print (fn [value]
                   (swap! client-state-atom assoc
                     :value-3 *2
                     :value-2 *1
                     :value-1 value
                     :ns *ns*)
                   (reset! return ["ok" value])
                   (if (pretty-print?)
                     (pprint value)
                     (prn value)))))
      (finally (.flush out-pw)))
    
    {:out (str out)
     :ns (-> @client-state-atom :ns .name str)
     :status (first @return)
     :value (pr-str (second @return))}))

(def #^{:private true
        :doc "Currently one minute; this can't just be Long/MAX_VALUE, or we'll inevitably
              foul up the executor's threadpool with hopelessly-blocked threads.
              This can be overridden on a per-request basis by the client."}
       default-timeout (* 1000 60))

(defn- handle-response
  [#^Future future
   {:keys [id timeout] :or {timeout default-timeout}}
   write-message]
  (try
    (let [result (.get future timeout TimeUnit/MILLISECONDS)]
      (write-message (assoc (select-keys result [:status :value :out :ns])
                       :id id)))
    (catch CancellationException e
      (write-message {:id id :status "cancelled"}))
    (catch TimeoutException e
      (write-message {:id id :status "timeout"}))
    (catch ExecutionException e
      ; this should never happen, insofar as clojure.main/repl catches all Throwables
      (.printStackTrace e)
      (write-message {:id id :status "server-failure"
                      :error "ExecutionException; this is probably an nREPL bug."}))
    (catch InterruptedException e
      ; I'm not clear as to when this can happen; if a thread pool thread is interrupted
      ; in conjunction with a cancellation, that's reported separately above...
      (.printStackTrace e)
      (write-message {:id id :status "server-failure"
                      :error "InterruptedException; this might be an nREPL bug"}))))
  
(defn- message-dispatch
  [client-state read-message write-message]
  (let [{:keys [id code] :as msg} (read-message)]
    (if-not code
      (write-message {:status "error"
                      :error "Received message with no code."})
      (let [future (submit #(#'handle-request client-state msg))]
        (swap! repl-futures assoc id future)
        (submit #(try
                   (handle-response future msg write-message)
                   (finally
                     (swap! repl-futures dissoc id))))))))

(defn- configure-streams
  [#^java.net.Socket sock]
  [(-> sock .getInputStream (InputStreamReader. "UTF-8") BufferedReader. PushbackReader.)
   (-> sock .getOutputStream (OutputStreamWriter. "UTF-8") BufferedWriter.)])

(defn- accept-connection
  [#^ServerSocket ss]
  (let [sock (.accept ss)
        [in out] (configure-streams sock)
        client-state (atom (init-client-state))]
    (submit-looping (partial message-dispatch
                      client-state
                      (partial read-message in)
                      (partial write-message out)))))

(defn start-server
  ([] (start-server 0))
  ([port]
    (let [ss (ServerSocket. port)]
      [ss (submit-looping (partial accept-connection ss))])))

(defn- client-send
  "Sends a new message via the write fn containing
   at minimum the provided code string and an id (optionally included as part
   of the kwargs), along with any other options specified in the kwards.
   Returns the id of the sent message as returned by the write-message fn."
  [write code & options]
  (let [{:keys [id] :as options} (apply hash-map options)]
    (write (assoc options
             :id (or id (str (java.util.UUID/randomUUID)))
             :code (str code "\n")))))

(defn send-and-wait
  "Synchronously sends code (and optional options) over the provided connection,
   and then waits for and returns the first associated response message.
   Note that other received messages are simply discarded.  This is intended
   for fully-synchronous operation, and assumes that no other messages are being
   sent or received using the same connection."
  [{:keys [send receive]} code & options]
  (let [id (apply send code options)]
    (loop []
      (let [msg (receive)]
        (if (= id (:id msg))
          msg
          (recur))))))

(defn read-response-value
  [response-message]
  (update-in response-message [:value] #(when % (read-string %))))

(defn connect
  "Connects to a hosted REPL at the given host (defaults to localhost) and port,
   returning a map containing three functions:

   - send: a function that takes at least one argument (a code string
           to be evaluated) and a variety of optional kwargs:
           :timeout - number in milliseconds specifying the maximum runtime of
                      accompanying code (default: 60000, one minute)
           :id - a string message ID (default: a randomly-generated UUID)
   - receive: a no-arg function that returns a message sent by the remote REPL
   - close: no-arg function that closes the underlying socket

   Note that the connection/map object also implements java.io.Closeable,
   and is therefore usable with with-open."
  ([port] (connect nil port))
  ([#^String host #^Integer port]
    (let [sock (java.net.Socket. (or host "localhost") port)
          [in out] (configure-streams sock)]
      (proxy [clojure.lang.PersistentArrayMap java.io.Closeable]
        [(into-array Object [:send (partial client-send (partial write-message out))
                             :receive (partial read-message in)
                             :close #(.close sock)])]
        (close [] (.close sock))))))


;; TODO
;; - ack
;; - support for multiple response messages (:seq key)
;; - command-line support for starting server, connecting to server, and optionally running other clojure script(s)/java mains
;; - HELO, init handshake, version compat check, etc