(ns nrepl.version
  "nREPL version information, read from the Maven-generated POM properties
  at build time."
  {:author "Colin Jones"
   :added  "0.5"}
  (:require [clojure.java.io :as io])
  (:import java.util.Properties))

(def ^:private group "nrepl")
(def ^:private artifact "nrepl")
(def ^:private properties-filename
  (str "META-INF/maven/" group "/" artifact "/pom.properties"))

(defn- map-from-property-filepath [file]
  (try
    (with-open [rdr (io/reader (io/resource file))]
      (let [props (Properties.)]
        (.load props rdr)
        (into {} props)))
    (catch Exception _e nil)))

(defn- get-version
  "Attempts to get the project version from system properties, or a properties
  file based on the group and artifact ids (in jars built by tools.build), or a
  default version passed in.  Falls back to an empty string when no default is
  present."
  ([] (get-version ""))
  ([default-version]
   (or (System/getProperty (str artifact ".version"))
       (get (map-from-property-filepath properties-filename) "version")
       default-version)))

(def ^:private version-string
  "Current version of nREPL as a string.
  See also `version`."
  (get-version))

(defn- parse-version
  "Splits a version string into a map like `*clojure-version*`, where
  :qualifier is nil for a release."
  [version-string]
  (let [[major minor incremental qualifier]
        (rest (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)" version-string))
        ->int #(some-> % Integer/parseInt)]
    {:major (->int major)
     :minor (->int minor)
     :incremental (->int incremental)
     :qualifier (not-empty qualifier)
     :version-string version-string}))

(def version
  "Current version of nREPL.
  Map of :major, :minor, :incremental, :qualifier, and :version-string."
  (parse-version version-string))
