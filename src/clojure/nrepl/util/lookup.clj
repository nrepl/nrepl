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
   [nrepl.misc :as misc]))

(defn special-sym-meta
  [sym]
  ;; clojure.repl/special-doc is private, so we need to work a
  ;; bit to be able to invoke it
  (let [f (requiring-resolve 'clojure.repl/special-doc)]
    (assoc (f sym)
           :ns "clojure.core"
           :file "clojure/core.clj"
           :special-form "true")))

(defn normal-sym-meta
  [ns sym]
  (some-> (ns-resolve ns sym) meta))

(defn- ns-file
  "The classpath-relative path of the file `the-ns` loads from, if any."
  [the-ns]
  (let [base (-> (name (ns-name the-ns)) (.replace \- \_) (.replace \. \/))]
    (first (filter io/resource (map #(str base %) [".clj" ".cljc"])))))

(defn ns-sym-meta
  "The metadata of the namespace `sym` names, an alias in `ns` or the
  namespace's own name."
  [ns sym]
  (when-let [the-ns (or (get (ns-aliases ns) sym) (find-ns sym))]
    (let [ns-sym (ns-name the-ns)
          file (ns-file the-ns)]
      (cond-> (assoc (meta the-ns) :ns ns-sym :name ns-sym)
        file (assoc :file file)))))

(defn sym-meta
  [ns sym]
  (if (special-symbol? sym)
    (special-sym-meta sym)
    (or (normal-sym-meta ns sym)
        (ns-sym-meta ns sym))))

(defn lookup
  "Lookup the metadata for `sym`, a var, a special form or a namespace.
  If the `sym` is not qualified then it will be resolved in the context
  of `ns`, where it can also be a namespace's alias."
  [ns sym]
  (some-> (sym-meta ns sym) misc/sanitize-meta))
