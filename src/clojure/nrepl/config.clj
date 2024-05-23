(ns nrepl.config
  "Server configuration utilities.
  Some server options can be configured via configuration
  files (local or global).  This namespace provides
  convenient API to work with them.

  The config resolution algorithm is the following:
  The global config file .nrepl/nrepl.edn is merged with
  any local config file (.nrepl.edn) if present.
  The values in the local config file take precedence."
  {:author "Bozhidar Batsov"
   :added  "0.5"}
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

(def ^:private home-dir
  "The user's home directory."
  (System/getProperty "user.home"))

(def config-dir
  "nREPL's configuration directory.
  By default it's ~/.nrepl, but this can be overridden
  with the NREPL_CONFIG_DIR env variable."
  (or (some-> (System/getenv "NREPL_CONFIG_DIR") io/file)
      (io/file home-dir ".nrepl")))

(def config-file
  "nREPL's config file."
  (io/file config-dir "nrepl.edn"))

(defn- load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  [source]
  (with-open [r (io/reader source)]
    (edn/read (java.io.PushbackReader. r))))

(defn- load-config
  "Load the configuration file identified by `file`.
  Return its contents as EDN if the file exists,
  or an empty map otherwise."
  [^java.io.File file]
  (if (.exists file)
    (load-edn file)
    {}))

(def config
  "Configuration map.
  It's created by merging the global configuration file
  with a local configuration file that would normally
  the placed in the directory in which you're running
  nREPL."
  (merge
   (load-config config-file)
   (load-config (io/file ".nrepl.edn"))))
