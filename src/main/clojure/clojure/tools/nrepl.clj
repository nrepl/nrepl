(ns #^{:doc ""
       :author "Chas Emerick"}
  clojure.tools.nrepl
  (:require [clojure.tools.nrepl.transport :as transport]
            clojure.main
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:use [clojure.tools.nrepl.misc :only (uuid)])
  (:import clojure.lang.LineNumberingPushbackReader
           (java.io Reader StringReader Writer PrintWriter)))

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
   send a given message on the transport before returning the seq."
  [transport response-timeout]
  (let [latest-head (atom nil)
        update #(swap! latest-head
                       (fn [[timestamp seq :as head] now]
                         (if (< timestamp now)
                           [now %]
                           head))
                       ; nanoTime appropriate here; looking to maintain ordering, not actual timestamps
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
  (comp (partial take-until (comp #(seq (clojure.set/intersection % termination-statuses))
                                  set
                                  :status)) 
        (let [keys (keys delimited-slots)]
          (partial filter #(= delimited-slots (select-keys % keys))))
        client
        #(merge % delimited-slots)))

(defn message
  "Returns a function of one argument.  Accepts a message that is sent via the
   transport-seq fn provided with a fixed :session id added to it.  Returns the
   head of the client's response-seq, filtered to include only
   messages related to the :session id that will terminate when the session is
   closed."
  [client {:keys [id] :as msg :or {id (uuid)}}]
  (let [f (delimited-transport-seq client #{"done"} {:id id})]
    (f (assoc msg :id id))))

(defn new-session
  "Provokes the creation and retention of a new session, optionally as a clone
   of an existing retained session, the id of which must be provided as a :clone
   kwarg.  Returns the new session's id."
  [client & {:keys [clone]}]
  (let [resp (first (message client (merge {:op :clone} (when clone {:session clone}))))]
    (or (:new-session resp)
        (throw (IllegalStateException.
                 (str "Could not open new session; :clone response: " resp))))))

(defn client-session
  "Returns a function of one argument.  Accepts a message that is sent via the
   transport-seq fn provided with a fixed :session id added to it.  Returns the
   head of the client's response-seq, filtered to include only
   messages related to the :session id that will terminate when the session is
   closed."
  [client & {:keys [session clone]}]
  (let [session (or session (apply new-session client (when clone [:clone clone])))]
    (delimited-transport-seq client #{"session-closed"} {:session session})))

(defn combine-responses
  "Combines the provided response messages into a single response map.
   Typical usage being:

       (combine-responses (repl-response-seq (eval session \"(some-expression)\")))

   Certain message slots are combined in special ways:

     - only the last :ns is retained
     - :value is accumulated into an ordered collection
     - :status is accumulated into a set
     - string values (associated with e.g. :out and :err) are concatenated"
  [responses]
  (reduce
    (fn [m [k v]]
      (case k
        (:id :ns) (assoc m k v)
        :value (update-in m [k] (fnil conj []) v)
        :status (update-in m [k] (fnil into #{}) v)
        (if (string? v)
          (update-in m [k] #(str % v))
          (assoc m k v))))            
    {} (apply concat responses)))

(defmacro code
  "Expands into a string consisting of the macro's body's forms
   (literally, no interpolation/quasiquoting of locals or other
   references), suitable for use in an :eval message, e.g.:

   {:op :eval, :code (code (+ 1 1) (slurp \"foo.txt\"))}"
  [& body]
  (apply str (map pr-str body)))

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

#_(defn response-values
  [response-fn]
  (->> response-fn
    response-seq
    (map read-response-value)
    combine-responses
    :value))

;; TODO session expiration
;; Ideas:
;; - tools
;;   - support syntax-quoting of vals in eval-msg
;; - protocols and transport
;;   - console
;;   - JMX
;;   - STOMP?
;;   - HTTP
;;   - websockets (although, is this interesting at all given an HTTP option?)
;; - cmdline
;;   - support for connecting to a server
;;   - optionally running other clojure script(s)/java mains prior to starting/connecting to a server

(defn connect
  "Connects to a hosted REPL at the given host (defaults to localhost) and port,
   returning a Session.

   Note that the Session also implements java.io.Closeable, and is therefore usable with
   with-open.

   Response functions, returned from invocations of (send ...), accept zero or
   one argument. The one-arg arity accepts either:
       - a number of milliseconds, which is the maximum time that the invocation will block
         before returning the next response message.  If the timeout is exceeded, nil
         is returned.  Multiple response messages are expected for each sent request; a
         response message with a :status of \"done\" indicates that the associated request
         has been fully processed, and that no further response messages should be expected.
         See response-seq and combine-responses for some utilities for consuming message
         responses."
  [& {:keys [port host]}]
  {:pre [port]}
  (transport/bencode (java.net.Socket. (or host "localhost") port)))

(def connect-defaults
  {"nrepl" {:transport-fn transport/bencode
            :port 7888}
   "telnet" {:transport-fn transport/terminal}})

(defn url-connect
  [url]
  (let [u (if (string? url)
            (java.net.URI. url)
            url)
        {:keys [transport-fn port]} (connect-defaults (.getScheme u))
        port (or (.getPort u) port)]
    (when-not port (throw (IllegalArgumentException.
                            (str "No port specified in " u))))
    (when-not transport-fn (throw (IllegalArgumentException.
                                    (str "No transport known for specified scheme " u))))
    (connect :host (.getHost u)
             :port port
             :transport-fn transport-fn)))

(def version
  (when-let [in (.getResourceAsStream (class connect) "/clojure/tools/nrepl/version.txt")]
    (with-open [^java.io.BufferedReader reader (io/reader in)]
      (->> (.readLine reader)
        (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
        rest
        (zipmap [:major :minor :incremental :qualifier])))))
