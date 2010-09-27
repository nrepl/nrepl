(ns cemerick.nrepl
  (:require clojure.main)
  (:import (java.net ServerSocket)
    java.lang.ref.WeakReference
    java.util.LinkedHashMap
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
                    (reset! return ["error" e])
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
      (write-message {:id id :status "interrupted"}))
    (catch TimeoutException e
      (write-message {:id id :status "timeout"})
      (interrupt id))
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

(defn- client-message
  "Returns a new message containing
   at minimum the provided code string and a generated unique id,
   along with any other options specified in the kwargs."
  [code & options]
  (assoc (apply hash-map options)
    :id (str (java.util.UUID/randomUUID))
    :code (str code "\n")))

(defn read-response-value
  "Returns the provided response message, replacing its :value string with
   the result of (read)ing it."
  [response-message]
  (update-in response-message [:value] #(when % (read-string %))))

(defn- send-client-message
  [response-promises out & message-args]
  (let [outgoing-msg (apply client-message message-args)
        p (promise)
        msg (assoc outgoing-msg ::response-promise p)]
    (.put response-promises (:id msg) (WeakReference. msg))
    (write-message out outgoing-msg)
    (fn response
      ([] (response default-timeout))
      ([x]
        (if (= :interrupt x)
          ((send-client-message
             response-promises
             out
             (format "(cemerick.nrepl/interrupt \"%s\")" (:id msg))))
          (try
            (.get (future @p) x TimeUnit/MILLISECONDS)
            (catch TimeoutException e)))))))

(defn- response-promises-map
  "Here only so we can force a connection to use a given map in tests
   to ensure that messages/promises are being released
   in conjunction with their associated response fns."
  []
  (java.util.Collections/synchronizedMap
    (java.util.WeakHashMap.)))

(defn- get-root-cause
  "Returns the root cause of the given Throwable."
  [#^Throwable t]
  (loop [c t]
    (if-let [c (.getCause c)]
      (recur c)
      c)))

(defn connect
  "Connects to a hosted REPL at the given host (defaults to localhost) and port,
   returning a map containing two functions:

   - send: a function that takes at least one argument (a code string
           to be evaluated) and optional kwargs:
           :timeout - number in milliseconds specifying the maximum runtime of
                      accompanying code (default: 60000, one minute)
           (send ...) returns a response function, described below.
   - close: no-arg function that closes the underlying socket

   Note that the connection/map object also implements java.io.Closeable,
   and is therefore usable with with-open.

   Response functions, returned from invocations of (send ...), accept zero or
   one argument. The one-arg arity accepts either:
       - a number of milliseconds, which is the maximum time that the invocation will block
         before returning the associated response message.  If the timeout is exceeded, nil
         is returned.
       - the :interrupt keyword. This sends an interrupt message for the request
         associated with the response function, and blocks for default-timeout milliseconds
         for confirmation of the interrupt.
   
   The 0-arg response function arity is the same as invoking (receive-fn default-timeout)."
  ([port] (connect nil port))
  ([#^String host #^Integer port]
    (let [sock (java.net.Socket. (or host "localhost") port)
          [in out] (configure-streams sock)
          ; Map<message-id, WeakReference<client-message>>
          ; this works as an "expiration" mechanism because the response fn
          ; is the only thing that closes over the client-message, which is
          ; where the message-id is sourced
          response-promises (response-promises-map)]
      (future (try
                (loop []
                  (let [response (read-message in)
                        #^WeakReference msg-ref (get response-promises (:id response))]
                    (when msg-ref
                      (deliver (-> msg-ref .get ::response-promise) response)))
                  (when-not (.isClosed sock) (recur)))
                (catch Throwable t
                  (let [root (get-root-cause t)]
                    (when-not (and (instance? java.net.SocketException)
                                (.isClosed sock))
                      ; TODO need to get this pushed into an atom so clients can see what's gone sideways
                      (.printStackTrace t)
                      (throw t))))))
      (proxy [clojure.lang.PersistentArrayMap java.io.Closeable]
        [(into-array Object [:send (partial send-client-message response-promises out)
                             :close #(.close sock)])]
        (close [] (.close sock))))))

; could be a lot fancier, but it'll do for now
(def #^{:private true} ack-port-promise (atom nil))

(defn reset-ack-port!
  []
  (reset! ack-port-promise (promise))
  ; save people the misery of ever trying to deref the empty promise in their REPL
  nil)

(defn wait-for-ack!
  [timeout]
  (let [#^Future f (future @@ack-port-promise)]
    (try
      (.get f timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e))))

(defn- send-ack
  [my-port ack-port]
  (let [connection (connect "localhost" ack-port)]
    (((:send connection) (format "(deliver @@#'cemerick.nrepl/ack-port-promise %d)" my-port)))))

(defn start-server
  ([] (start-server 0))
  ([port] (start-server port 0))
  ([port ack-port]
    (let [ss (ServerSocket. port)
          accept-future (submit-looping (partial accept-connection ss))]
      [ss accept-future (when (pos? ack-port)
                          (send-ack (.getLocalPort ss) ack-port))])))

;; TODO
;; - add convenience fns for toggling pprinting
;; - websockets adapter
;; - support for multiple response messages (:seq msg), making getting incremental output from long-running invocations possible/easy
;; - proper error handling on the receive loop
;; - command-line support for starting server, connecting to server, and optionally running other clojure script(s)/java mains
;; - HELO, init handshake, version compat check, etc