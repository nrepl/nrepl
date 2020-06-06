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
  "Returns the metadata for the special symbol `sym`."
  [sym]
  ;; clojure.repl/special-doc is private, so we need to work a
  ;; bit to be able to invoke it
  (let [f (misc/requiring-resolve 'clojure.repl/special-doc)]
    (assoc (f sym)
           :ns "clojure.core"
           :file "clojure/core.clj"
           :special-form "true")))

(defn fully-qualified-sym-meta
  "Returns the metadata for the fully-qualified symbol `sym` (e.g. `clojure.core/str`)."
  [sym]
  (if-let [var (resolve sym)]
    (meta var)))

(defn ns-alias
  "Resolves the namespace alias for `sym` (e.g. `str/split` in the context of `ns` (if any)."
  [ns sym]
  (if-let [qualifier (first (str/split (str sym) #"/"))]
    (get (ns-aliases (symbol ns)) (symbol qualifier))))

(defn aliased-sym-meta
  "Returns the metadata for a symbol `sym` aliased in `ns` (e.g. `str/split`)."
  [ns sym]
  (if-let [ns-alias (ns-alias ns sym)]
    (let [unqualified-sym (second (str/split (str sym) #"/"))
          fully-qualified-sym (symbol (str ns-alias "/" unqualified-sym))]
      (fully-qualified-sym-meta fully-qualified-sym))))

(defn sym-meta
  "Returns the metadata for symbol `sym` in the context of `ns`."
  [ns sym]
  (cond
    (special-symbol? sym) (special-sym-meta sym)
    ;; handles symbols like str/split
    (and (qualified-symbol? sym) (ns-alias ns sym)) (aliased-sym-meta ns sym)
    ;; handles fully qualified symbols like clojure.core/str
    (qualified-symbol? sym) (fully-qualified-sym-meta sym)
    ;; handles unqualified symbols like foo
    :else (fully-qualified-sym-meta (qualify-sym ns sym))))

(defn normalize-meta
  "Ensures that the metadata `m` is in a format that's bencode compatible."
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
