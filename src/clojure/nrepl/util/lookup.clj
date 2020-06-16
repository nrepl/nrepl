(ns nrepl.util.lookup
  "Symbol info lookup.

  It's meant to provide you with useful data like definition location,
  parameter lists, etc.

  NOTE: The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nrepl.misc :as misc]))

;;; Var meta logic
(def var-meta-whitelist
  "A list of var metadata attributes are safe to return to the clients.
  We need to guard ourselves against EDN data that's not encodeable/decodable
  with bencode. We also optimize the response payloads by not returning
  redundant metadata."
  [:ns :name :doc :file :arglists :forms :macro :special-form
   :protocol :line :column :added :deprecated :resource])

(defn special-sym-meta
  [sym]
  ;; clojure.repl/special-doc is private, so we need to work a
  ;; bit to be able to invoke it
  (let [f (misc/requiring-resolve 'clojure.repl/special-doc)]
    (assoc (f sym)
           :ns "clojure.core"
           :file "clojure/core.clj"
           :special-form "true")))

(defn normal-sym-meta
  [ns sym]
  (if-let [var (ns-resolve ns sym)]
    (meta var)))

(defn sym-meta
  [ns sym]
  (if (special-symbol? sym)
    (special-sym-meta sym)
    (normal-sym-meta ns sym)))

(defn resolve-file
  [path]
  (if-let [resource (io/resource path)]
    (str resource)
    path))

(defn normalize-meta
  [m]
  (-> m
      (select-keys var-meta-whitelist)
      (update :ns str)
      (update :name str)
      (update :file resolve-file)
      (cond-> (:macro m) (update :macro str))
      (assoc :arglists-str (str (:arglists m)))))

(defn lookup
  "Lookup the metadata for `sym`.
  If the `sym` is not qualified than it will be resolved in the context
  of `ns`."
  [ns sym]
  (if-let [m (sym-meta ns sym)]
    (normalize-meta m)))
