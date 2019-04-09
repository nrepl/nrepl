(ns nrepl.core
  "High level nREPL client support."
  {:author "Chas Emerick"}
  (:require
   clojure.set
   [nrepl.misc :refer [uuid]]
   [nrepl.transport :as transport]
   [nrepl.version :as version])
  (:import
   clojure.lang.LineNumberingPushbackReader
   [java.io Reader StringReader Writer PrintWriter]))

(defn response-seq
  "Returns a lazy seq of messages received via the given Transport.
   Called with no further arguments, will block waiting for each message.
   The seq will end only when the underlying Transport is closed (i.e.
   returns nil from `recv`) or if a message takes longer than `timeout`
   millis to arrive."
  ([transport] (response-seq transport Long/MAX_VALUE))
  ([transport timeout]
   (take-while identity (repeatedly #(transport/recv transport timeout)))))

(defn client
  "Returns a fn of zero and one argument, both of which return the current head of a single
   response-seq being read off of the given client-side transport.  The one-arg arity will
   send a given message on the transport before returning the seq.

   Most REPL interactions are best performed via `message` and `client-session` on top of
   a client fn returned from this fn."
  [transport response-timeout]
  (let [latest-head (atom nil)
        update #(swap! latest-head
                       (fn [[timestamp seq :as head] now]
                         (if (< timestamp now)
                           [now %]
                           head))
                       ;; nanoTime appropriate here; looking to maintain ordering, not actual timestamps
                       (System/nanoTime))
        tracking-seq (fn tracking-seq [responses]
                       (lazy-seq
                        (if (seq responses)
                          (let [rst (tracking-seq (rest responses))]
                            (update rst)
                            (cons (first responses) rst))
                          (do (update nil) nil))))
        restart #(let [head (-> transport
                                (response-seq response-timeout)
                                tracking-seq)]
                   (reset! latest-head [0 head])
                   head)]
    ^{::transport transport ::timeout response-timeout}
    (fn this
      ([] (or (second @latest-head)
              (restart)))
      ([msg]
       (transport/send transport msg)
       (this)))))

(defn- take-until
  "Like (take-while (complement f) coll), but includes the first item in coll that
   returns true for f."
  [f coll]
  (let [[head tail] (split-with (complement f) coll)]
    (concat head (take 1 tail))))

(defn- delimited-transport-seq
  [client termination-statuses delimited-slots]
  (with-meta
    (comp (partial take-until (comp #(seq (clojure.set/intersection % termination-statuses))
                                    set
                                    :status))
          (let [keys (keys delimited-slots)]
            (partial filter #(= delimited-slots (select-keys % keys))))
          client
          #(merge % delimited-slots))
    (-> (meta client)
        (update-in [::termination-statuses] (fnil into #{}) termination-statuses)
        (update-in [::taking-until] merge delimited-slots))))

(defn message
  "Sends a message via [client] with a fixed message :id added to it.
   Returns the head of the client's response seq, filtered to include only
   messages related to the message :id that will terminate upon receipt of a
   \"done\" :status."
  [client {:keys [id] :as msg :or {id (uuid)}}]
  (let [f (delimited-transport-seq client #{"done" :done} {:id id})]
    (f (assoc msg :id id))))

(defn new-session
  "Provokes the creation and retention of a new session, optionally as a clone
   of an existing retained session, the id of which must be provided as a :clone
   kwarg.  Returns the new session's id."
  [client & {:keys [clone]}]
  (let [resp (first (message client (merge {:op "clone"} (when clone {:session clone}))))]
    (or (:new-session resp)
        (throw (IllegalStateException.
                (str "Could not open new session; :clone response: " resp))))))

(defn client-session
  "Returns a function of one argument.  Accepts a message that is sent via the
   client provided with a fixed :session id added to it.  Returns the
   head of the client's response seq, filtered to include only
   messages related to the :session id that will terminate when the session is
   closed."
  [client & {:keys [session clone]}]
  (let [session (or session (apply new-session client (when clone [:clone clone])))]
    (delimited-transport-seq client #{"session-closed"} {:session session})))

(defn combine-responses
  "Combines the provided seq of response messages into a single response map.

   Certain message slots are combined in special ways:

     - only the last :ns is retained
     - :value is accumulated into an ordered collection
     - :status and :session are accumulated into a set
     - string values (associated with e.g. :out and :err) are concatenated"
  [responses]
  (reduce
   (fn [m [k v]]
     (case k
       (:id :ns) (assoc m k v)
       :value (update-in m [k] (fnil conj []) v)
       :status (update-in m [k] (fnil into #{}) v)
       :session (update-in m [k] (fnil conj #{}) v)
       (if (string? v)
         (update-in m [k] #(str % v))
         (assoc m k v))))
   {} (apply concat responses)))

(defn code*
  "Returns a single string containing the pr-str'd representations
   of the given expressions."
  [& expressions]
  (apply str (map pr-str expressions)))

(defmacro code
  "Expands into a string consisting of the macro's body's forms
   (literally, no interpolation/quasiquoting of locals or other
   references), suitable for use in an `\"eval\"` message, e.g.:

   {:op \"eval\", :code (code (+ 1 1) (slurp \"foo.txt\"))}"
  [& body]
  (apply code* body))

(defn read-response-value
  "Returns the provided response message, replacing its :value string with
   the result of (read)ing it.  Returns the message unchanged if the :value
   slot is empty or not a string."
  [{:keys [value] :as msg}]
  (if-not (string? value)
    msg
    (try
      (assoc msg :value (read-string value))
      (catch Exception e
        (throw (IllegalStateException. (str "Could not read response value: " value) e))))))

(defn response-values
  "Given a seq of responses (as from response-seq or returned from any function returned
   by client or client-session), returns a seq of values read from :value slots found
   therein."
  [responses]
  (->> responses
       (map read-response-value)
       combine-responses
       :value))

(defn connect
  "Connects to a socket-based REPL at the given host (defaults to 127.0.0.1) and port,
   returning the Transport (by default `nrepl.transport/bencode`)
   for that connection.

   Transports are most easily used with `client`, `client-session`, and
   `message`, depending on the semantics desired."
  [& {:keys [port host transport-fn] :or {transport-fn transport/bencode
                                          host "127.0.0.1"}}]
  {:pre [transport-fn port]}
  (transport-fn (java.net.Socket. ^String host (int port))))

(defn- ^java.net.URI to-uri
  [x]
  {:post [(instance? java.net.URI %)]}
  (if (string? x)
    (java.net.URI. x)
    x))

(defn- socket-info
  [x]
  (let [uri (to-uri x)
        port (.getPort uri)]
    (merge {:host (.getHost uri)}
           (when (pos? port)
             {:port port}))))

(def ^{:private false} uri-scheme #(-> (to-uri %) .getScheme .toLowerCase))

(defmulti url-connect
  "Connects to an nREPL endpoint identified by the given URL/URI.  Valid
   examples include:

      nrepl://192.168.0.12:7889
      telnet://localhost:5000
      http://your-app-name.heroku.com/repl

   This is a multimethod that dispatches on the scheme of the URI provided
   (which can be a string or java.net.URI).  By default, implementations for
   nrepl (corresponding to using the default bencode transport) and
   telnet (using the `nrepl.transport/tty` transport) are
   registered.  Alternative implementations may add support for other schemes,
   such as HTTP, HTTPS, JMX, existing message queues, etc."
  uri-scheme)

;; TODO: oh so ugly
(defn- add-socket-connect-method!
  [protocol connect-defaults]
  (defmethod url-connect protocol
    [uri]
    (apply connect (mapcat identity
                           (merge connect-defaults
                                  (socket-info uri))))))

(add-socket-connect-method! "nrepl+edn" {:transport-fn transport/edn
                                         :port 7888})
(add-socket-connect-method! "nrepl" {:transport-fn transport/bencode
                                     :port 7888})
(add-socket-connect-method! "telnet" {:transport-fn transport/tty})

(defmethod url-connect :default
  [uri]
  (throw (IllegalArgumentException.
          (format "No nREPL support known for scheme %s, url %s" (uri-scheme uri) uri))))

(def ^{:deprecated "0.5.0"} version
  "Use `nrepl.version/version` instead.
  Current version of nREPL.
  Map of :major, :minor, :incremental, :qualifier, and :version-string."
  version/version)

(def ^{:deprecated "0.5.0"} version-string
  "Use `(:version-string nrepl.version/version)` instead.
  Current version of nREPL as a string.
  See also `version`."
  (:version-string version/version))
