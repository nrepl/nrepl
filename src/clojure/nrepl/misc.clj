(ns nrepl.misc
  "Misc utilities used in nREPL's implementation (potentially also
  useful for anyone extending it)."
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [requiring-resolve])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl.config :refer [config]]))

(defn log
  [ex-or-msg & msgs]
  (let [ex (when (instance? Throwable ex-or-msg) ex-or-msg)
        msgs (if ex msgs (filter identity (cons ex-or-msg msgs)))]
    (binding [*out* *err*]
      (apply println "ERROR:" msgs)
      (when ex (.printStackTrace ^Throwable ex)))))

(defmacro noisy-future
  "Executes body in a future, logging any exceptions that make it to the
  top level."
  [& body]
  `(future
     (try
       ~@body
       (catch Throwable ex#
         (log ex#)
         (throw ex#)))))

(defmacro returning
  "Executes `body`, returning `x`."
  {:style/indent 1}
  [x & body]
  `(let [x# ~x] ~@body x#))

(defn uuid
  "Returns a new UUID string."
  []
  (str (java.util.UUID/randomUUID)))

(defn response-for
  "Returns a map containing the :session and :id from the \"request\" `msg`
   as well as all entries specified in `response-data`, which can be one
   or more maps (which will be merged), *or* key-value pairs.

   (response-for msg :status :done :value \"5\")
   (response-for msg {:status :interrupted})

   The :session value in `msg` may be any Clojure reference type (to accommodate
   likely implementations of sessions) that has an :id slot in its metadata,
   or a string."
  [{:keys [session id]} & response-data]
  {:pre [(seq response-data)]}
  (let [{:keys [status] :as response} (if (map? (first response-data))
                                        (reduce merge response-data)
                                        (apply hash-map response-data))
        response (if (not status)
                   response
                   (assoc response :status (if (coll? status)
                                             status
                                             #{status})))
        basis (merge (when id {:id id})
                     ;; AReference should make this suitable for any session implementation?
                     (when session {:session (if (instance? clojure.lang.AReference session)
                                               (-> session meta :id)
                                               session)}))]
    (merge basis response)))

(defn requiring-resolve
  "Resolves namespace-qualified sym per 'resolve'. If initial resolve fails,
  attempts to require sym's namespace and retries. Returns nil if sym could not
  be resolved."
  [sym & [log?]]
  (or (resolve sym)
      (try
        (require (symbol (namespace sym)))
        (resolve sym)
        (catch Exception e
          (when log?
            (log e))))))

(defmacro with-session-classloader
  "Bind `clojure.lang.Compiler/LOADER` to the context classloader. This is
  required to get hotloading with pomegranate working under certain conditions."
  [& body]
  `(let [cl# (.getContextClassLoader (Thread/currentThread))]
     (with-bindings {clojure.lang.Compiler/LOADER cl#}
       ~@body)))

(defn parse-java-version
  "Parse Java version string according to JEP 223 and return version as a number."
  []
  (try (let [s (System/getProperty "java.specification.version")
             [major minor _] (str/split s #"\.")
             major (Integer/parseInt major)]
         (if (> major 1)
           major
           (Integer/parseInt minor)))
       (catch Exception _ 8)))

(def java-version "Current Java version number." (parse-java-version))

(defn java-8?
  "Util to check if we are using Java 8. Useful for features that behave
  differently after version 8."
  []
  (= java-version 8))

(defn attach-self-enabled?
  "Return true if the current process allows native agents to be attached from
  within the JVM itself."
  []
  ;; -Djdk.attach.allowAttachSelf sets the property to "" if it is present,
  ;; otherwise that property will be nil.
  (boolean (System/getProperty "jdk.attach.allowAttachSelf")))

(defn jvmti-agent-enabled?
  "Return true if nREPL is allowed to load its JVMTI agent at runtime."
  []
  (and (attach-self-enabled?)
       ;; JVMTI agent is a "soft opt-in" — if attachSelf is enabled, then we
       ;; consider this a permission to load the agent, UNLESS the user
       ;; explicitly opts out of it in the config.
       (boolean (:enable-jvmti-agent config true))))

(def safe-var-metadata
  "A list of var metadata attributes are safe to return to the clients.
  We need to guard ourselves against EDN data that's not encodeable/decodable
  with bencode. We also optimize the response payloads by not returning
  redundant metadata."
  [:ns :name :doc :file :arglists :forms :macro :special-form
   :protocol :line :column :added :deprecated :resource])

(defn- handle-file-meta
  "Convert :file metadata to string.
  Typically `value` would be a string, a File or an URL."
  [value]
  (when value
    (str (if (string? value)
           ;; try to convert relative file paths like "clojure/core.clj"
           ;; to absolute file paths
           (or (io/resource value) value)
           ;; If :file is a File or URL object we just return it as is
           ;; and convert it to string
           value))))

(defn sanitize-meta
  "Sanitize a Clojure metadata map such that it can be bencoded."
  [m]
  (-> m
      (select-keys safe-var-metadata)
      (update :ns str)
      (update :name str)
      (update :protocol str)
      (update :file handle-file-meta)
      (cond-> (:macro m) (update :macro str))
      (cond-> (:special-form m) (update :special-form str))
      (assoc :arglists-str (str (:arglists m)))
      (cond-> (:arglists m) (update :arglists str))))
