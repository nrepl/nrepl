(ns nrepl.util.lookup
  "Symbol info lookup.

  It's meant to provide you with useful data like definition location,
  parameter lists, etc.

  NOTE: The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:refer-clojure :exclude [qualified-symbol?])
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nrepl.misc :as misc]))

;;; Utility functions for dealing with symbols
(defn qualify-sym
  "Qualify a symbol, if any in `sym`, with `ns`.

  Return nil if `sym` is nil, attempting to generate a valid symbol even
  in case some `ns` is missing."
  {:added "0.5"}
  [ns sym]
  (when sym
    (symbol (some-> ns str) (str sym))))

(defn qualified-symbol?
  "Return true if `x` is a symbol with a namespace.

  This is only available from Clojure 1.9 so we backport it until we
  drop support for Clojure 1.8."
  {:added "0.5"}
  [x]
  (boolean (and (symbol? x) (namespace x) true)))

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

(defn qualified-sym-meta
  [ns sym]
  (if-let [var (ns-resolve ns sym)]
    (meta var)))

(defn sym-meta
  [ns sym]
  (cond
    (special-symbol? sym) (special-sym-meta sym)
    (qualified-symbol? sym) (qualified-sym-meta ns sym)
    :else (qualified-sym-meta ns (qualify-sym ns sym))))

(defn normalize-meta
  [m]
  (-> m
      (select-keys var-meta-whitelist)
      (update :ns str)
      (update :name str)
      (update :file (comp str io/resource))
      (assoc :arglists-str (str (:arglists m)))))

(defn lookup
  "Lookup the metadata for `sym`.
  If the `sym` is not qualified than it will be resolved in the context
  of `ns`."
  [ns sym]
  (if-let [m (sym-meta ns sym)]
    (normalize-meta m)))
