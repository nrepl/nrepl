(ns nrepl.config
  "Server configuration utilities.

  Most server options can be configured via configuration files (local or
  global), env variables, and Java properties. This namespace provides convenient
  API to work with them.

  The config resolution algorithm considers values in the following order:
  - global config file .nrepl/nrepl.edn
  - local config file (.nrepl.edn)
  - Java properties starting with `nrepl.`
  - env variables starting with `NREPL_`"
  {:author "Bozhidar Batsov, Oleksandr Yakushev"
   :added  "0.5"}
  (:refer-clojure :exclude [parse-boolean parse-long])
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [nrepl.misc :as misc]))

(defn- fail
  "Throws an exception with a message specified by `fmt` and `args`."
  [fmt & args]
  (throw (ex-info (apply format fmt args) {:nrepl/kind ::config-error})))

(defmacro ^:private try-log [& body]
  `(try ~@body
        (catch Exception e#
          (misc/log e#)
          (fail "nREPL config init failed"))))

(defn- parse-long
  "Parses string as a Long. Idempotent."
  [x]
  (if (int? x) x (Long/parseLong x)))

(defn- parse-boolean
  "Parses string as a Boolean. Idempotent."
  [x]
  (if (instance? Boolean x)
    x
    (not (#{nil "" "0" "false"} x))))

(defn- parse-string
  "Parses string as a symbol and resolve it to a var. Idempotent."
  [x]
  (if (string? x) x
      (fail "Bad string value: %s" x)))

(defn- parse-var-delay
  "Parses string as a symbol and returns a delay that resolves to a var.
  Idempotent."
  [x]
  (if (delay? x)
    x
    (let [sym (symbol x)]
      (delay (misc/requiring-resolve sym)))))

(defn- parse-var-list-delay
  "Parses string as a comma-separated list of symbols and returns a delay that
  resolves to a list of. Idempotent."
  [x]
  (cond (delay? x) x
        (symbol? x) (recur [x])
        (string? x) (recur (mapv symbol (str/split x #",")))
        (sequential? x) (delay (into [] (keep misc/requiring-resolve) x))
        :else (fail "Bad value for list of symbols: %s" x)))

(def ^:private parsers
  {:number         #'parse-long
   :boolean        #'parse-boolean
   :string         #'parse-string
   :var-delay      #'parse-var-delay
   :var-list-delay #'parse-var-list-delay})

(def ^:private config-blueprint "Configuation blueprint."
  {:port               {:type :number}
   :bind               {:type :string}
   :socket             {:type :string}
   :middleware         {:type :var-list-delay}
   :handler            {:type :var-delay}
   :transport-fn       {:type :var-delay
                        :default 'nrepl.transport/bencode}
   :repl-fn            {:type :var-delay
                        :default 'nrepl.cmdline/run-repl}
   :ack-port           {:type :number}
   :tls-keys-str       {:type :string}
   :tls-keys-file      {:type :string}

   :lookup-fn          {:type :var-delay
                        :default 'nrepl.util.lookup/lookup}
   :complete-fn        {:type :var-delay
                        :default 'nrepl.util.completion/completions}

   :enable-jvmti-agent {:type :boolean, :default true}})

(def ^:private config-state "Configuration state." (atom {}))
(def config "Deprecated, left for compatibility. Use (get-config) instead." {})

(defn- reset-state []
  (reset! config-state
          (->> (for [[k {:keys [type default] :as spec}] config-blueprint
                     :when (contains? spec :default)]
                 [k ((parsers type) default)])
               (into {})))
  (.bindRoot #'config @config-state))

(defn set-value
  "Set the `value` for the `key` in the configuration. Value can either be a
  string to be parsed or a destination type value."
  [key value]
  (if-some [bp (config-blueprint key)]
    (let [new-value ((parsers (:type bp)) value)]
      (swap! config-state assoc key new-value)
      (.bindRoot #'config @config-state))
    (fail "Unknown nREPL config key: %s" key)))

(defn- fill-from-env []
  (try-log
   (doseq [key (keys config-blueprint)
           :let [env-name (str "NREPL_" (str/replace (str/upper-case (name key)) "-" "_"))
                 value (System/getenv env-name)]
           :when value]
     (set-value key value))))

(defn- fill-from-properties []
  (try-log
   (let [env (System/getenv)]
     (doseq [key (keys config-blueprint)
             :let [prop-name (str "nrepl." (name key))
                   value (System/getProperty prop-name)]
             :when value]
       (set-value key value)))))

(defn fill-from-map [m]
  (try-log
   (doseq [[key value] m]
     (if (contains? config-blueprint key)
       (set-value key value)
       ;; For compatibility, assoc unknown values to config too.
       (swap! config-state assoc key value)))
   (.bindRoot #'config @config-state)))

(defn- read-edn-file
  "Read edn from a file."
  [^java.io.File file]
  (if (.exists file)
    (with-open [r (io/reader file)]
      (edn/read (java.io.PushbackReader. r)))
    {}))

(defn get-config
  "Configuration map.
  It's created by merging the global configuration file
  with a local configuration file that would normally
  the placed in the directory in which you're running
  nREPL."
  []
  @config-state)

(def ^:private config-dir
  "nREPL's configuration directory.
  By default it's ~/.nrepl, but this can be overridden
  with the NREPL_CONFIG_DIR env variable."
  (or (some-> (System/getenv "NREPL_CONFIG_DIR") io/file)
      (io/file (System/getProperty "user.home") ".nrepl")))

(reset-state)
(fill-from-map (read-edn-file (io/file config-dir "nrepl.edn"))) ;; Global
(fill-from-map (read-edn-file (io/file ".nrepl.edn"))) ;; Local
(fill-from-properties)
(fill-from-env)
