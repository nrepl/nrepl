(ns nrepl.util.completion
  "Code completion functionality.

  The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added  "0.8"}
  (:require [clojure.main])
  (:import [java.util.jar JarFile]
           [java.io File]
           [java.lang.reflect Member]
           [java.util.jar JarEntry]))

;; Code adapted from clojure-complete (https://github.com/ninjudd/clojure-complete)
;; The results follow compliment's format, as it's richer and gives more useful
;; data to clients.

;;; Utility functions
(defn namespaces
  "Returns a list of potential namespace completions for a given namespace"
  [ns]
  (concat (map ns-name (all-ns)) (keys (ns-aliases ns))))

(defn ns-public-vars
  "Returns a list of potential public var name completions for a given namespace"
  [ns]
  (vals (ns-publics ns)))

(defn ns-vars
  "Returns a list of all potential var name completions for a given namespace"
  [ns]
  (filter var? (vals (ns-map ns))))

(defn ns-classes
  "Returns a list of potential class name completions for a given namespace"
  [ns]
  (keys (ns-imports ns)))

(def special-forms
  '[def if do let quote var fn loop recur throw try monitor-enter monitor-exit dot new set!])

(defn- static? [#^java.lang.reflect.Member member]
  (java.lang.reflect.Modifier/isStatic (.getModifiers member)))

(defn ns-java-methods
  "Returns a list of Java method names for a given namespace."
  [ns]
  (distinct ; some methods might exist in multiple classes
   (for [class (vals (ns-imports ns)) method (.getMethods ^Class class) :when (static? method)]
     (str "." (.getName ^Member method)))))

(defn static-members
  "Returns a list of potential static members for a given class"
  [^Class class]
  (->> (concat (.getMethods class) (.getDeclaredFields class))
       (filter static?)
       (map #(.getName ^Member %))
       (distinct)))

(defn path-files [^String path]
  (cond (.endsWith path "/*")
        (for [^File jar (.listFiles (File. path)) :when (.endsWith ^String (.getName jar) ".jar")
              file (path-files (.getPath jar))]
          file)

        (.endsWith path ".jar")
        (try (for [^JarEntry entry (enumeration-seq (.entries (JarFile. path)))]
               (.getName entry))
             (catch Exception e))

        :else
        (for [^File file (file-seq (File. path))]
          (.replace ^String (.getPath file) path ""))))

(def classfiles
  (for [prop (filter #(System/getProperty %1) ["sun.boot.class.path" "java.ext.dirs" "java.class.path"])
        path (.split (System/getProperty prop) File/pathSeparator)
        ^String file (path-files path) :when (and (.endsWith file ".class") (not (.contains file "__")))]
    file))

(defn- classname [^String file]
  (.. file (replace ".class" "") (replace File/separator ".")))

(def top-level-classes
  (future
    (doall
     (for [file classfiles :when (re-find #"^[^\$]+\.class" file)]
       (classname file)))))

(def nested-classes
  (future
    (doall
     (for [file classfiles :when (re-find #"^[^\$]+(\$[^\d]\w*)+\.class" file)]
       (classname file)))))

(defn resolve-class [sym]
  (try (let [val (resolve sym)]
         (when (class? val) val))
       (catch Exception e
         (when (not= ClassNotFoundException
                     (class (clojure.main/repl-exception e)))
           (throw e)))))

;;; Candidates

(defn annotate-var [var]
  (let [{macro :macro arglists :arglists var-name :name} (meta var)
        type (cond macro :macro
                   arglists :function
                   :else :var)]
    {:candidate (name var-name) :type type}))

(defn annotate-class
  [cname]
  {:candidate cname :type :class})

(def special-form-candidates
  (map #(hash-map :candidate (name %) :type :special-form :ns "clojure.core") special-forms))

(defn ns-candidates
  [ns]
  (map #(hash-map :candidate (name %) :type :namespace) (namespaces ns)))

(defn ns-var-candidates
  [ns]
  (map annotate-var (ns-vars ns)))

(defn ns-public-var-candidates
  [ns]
  (map annotate-var (ns-public-vars ns)))

(defn ns-class-candidates
  [ns]
  (map #(hash-map :candidate (name %) :type :class) (ns-classes ns)))

(defn ns-java-method-candidates
  [ns]
  (for [method (ns-java-methods ns)]
    {:candidate method :type :method}))

(defn static-member-candidates
  [class]
  (for [name (static-members class)]
    {:candidate name :type :static-method}))

(defn scoped-candidates
  [prefix ns]
  (when-let [prefix-scope (first (.split prefix "/"))]
    (let [scope (symbol prefix-scope)]
      (map #(update % :candidate (fn [c] (str scope "/" c)))
           (if-let [class (resolve-class scope)]
             (static-member-candidates class)
             (when-let [ns (or (find-ns scope) (scope (ns-aliases ns)))]
               (ns-public-var-candidates ns)))))))

(defn class-candidates
  [prefix ns]
  (map annotate-class
       (if (.contains prefix "$")
         @nested-classes
         @top-level-classes)))

(defn generic-candidates
  [ns]
  (concat special-form-candidates
          (ns-candidates ns)
          (ns-var-candidates ns)
          (ns-class-candidates ns)))

(defn completion-candidates
  [prefix ns]
  (cond
    (.startsWith prefix ".") (ns-java-method-candidates ns)
    (.contains prefix "/")  (scoped-candidates prefix ns)
    (.contains prefix ".")  (concat (ns-candidates ns) (class-candidates prefix ns))
    :else                   (generic-candidates ns)))

(defn completions
  "Return a sequence of matching completion candidates given a prefix string and an optional current namespace."
  ([prefix]
   (completions prefix *ns*))
  ([prefix ns]
   (completions prefix ns nil))
  ([^String prefix ns _options]
   (let [candidates (completion-candidates prefix ns)]
     (sort-by :candidate (filter #(.startsWith (:candidate %) prefix) candidates)))))
