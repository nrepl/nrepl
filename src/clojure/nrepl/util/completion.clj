(ns nrepl.util.completion
  "Code completion functionality.

  This namespace is based on compliment-lite.
  (https://github.com/alexander-yakushev/compliment/tree/master/lite)

  The functionality here is experimental and the API is subject to changes."
  {:clj-kondo/config '{:linters {:unused-binding {:level :off}}}
   :author "Oleksandr Yakushev, Bozhidar Batsov"
   :added  "0.8"}
  (:require [clojure.string :as str])
  (:import java.io.File
           java.nio.file.Files
           (java.util.function Function Predicate)
           java.util.concurrent.locks.ReentrantLock
           (java.util.jar JarEntry JarFile)
           java.util.stream.Collectors
           (java.lang.reflect Field Member Method Modifier Constructor)
           java.util.HashSet))

;; Compliment lite was generated at Wed Sep 03 02:16:02 EEST 2025
;; SPDX-License-Identifier: EPL-1.0

;; compliment/utils.clj

(def ^{:dynamic true} *extra-metadata*
  "Signals to downstream sources which additional information about completion\n  candidates they should attach. Should be a set of keywords."
  nil)

(defn split-by-leading-literals
  "Separate quote/var/deref qualifiers from a var name."
  [symbol-str]
  (next (re-matches #"(@{0,2}#'|'|@)?(.*)" symbol-str)))

(set! *unchecked-math* true)

(defn fuzzy-matches?
  "Tests if symbol matches the prefix when symbol is split into parts on\n  separator."
  [^String prefix ^String symbol ^Character separator]
  (let [pn (.length prefix)
        sn (.length symbol)]
    (cond (zero? pn) true
          (zero? sn) false
          (not (= (.charAt prefix 0) (.charAt symbol 0))) false
          :else (loop [pi 1
                       si 1
                       skipping false]
                  (cond (>= pi pn) true
                        (>= si sn) false
                        :else (let [pc (.charAt prefix pi)
                                    sc (.charAt symbol si)
                                    match (= pc sc)]
                                (cond (= sc separator)
                                      (recur (if match (inc pi) pi) (inc si) false)
                                      (or skipping (not match)) (recur pi (inc si) true)
                                      match (recur (inc pi) (inc si) false))))))))

(defn fuzzy-matches-no-skip?
  "Tests if symbol matches the prefix where separator? checks whether character\n  is a separator. Unlike `fuzzy-matches?` requires separator characters to be\n  present in prefix."
  [^String prefix ^String symbol separator?]
  (let [pn (.length prefix)
        sn (.length symbol)]
    (cond (zero? pn) true
          (zero? sn) false
          (not (= (.charAt prefix 0) (.charAt symbol 0))) false
          :else (loop [pi 1
                       si 1
                       skipping false]
                  (cond (>= pi pn) true
                        (>= si sn) false
                        :else (let [pc (.charAt prefix pi)
                                    sc (.charAt symbol si)]
                                (cond skipping (if (separator? sc)
                                                 (recur pi si false)
                                                 (recur pi (inc si) true))
                                      (= pc sc) (recur (inc pi) (inc si) false)
                                      :else (recur pi (inc si) true))))))))

(set! *unchecked-math* false)

(defn resolve-class
  "Tries to resolve a classname from the given symbol, or returns nil\n  if classname can't be resolved."
  [ns sym]
  (when-let [val (try (ns-resolve ns sym) (catch ClassNotFoundException _))]
    (when (class? val) val)))

(defn resolve-namespace
  "Tries to resolve a namespace from the given symbol, either from a\n  fully qualified name or an alias in the given namespace."
  [sym ns]
  (or ((ns-aliases ns) sym) (find-ns sym)))

(def primitive-cache (atom {}))

(defn- classpath-strings
  []
  (into []
        (keep #(System/getProperty %))
        ["sun.boot.class.path" "java.ext.dirs" "java.class.path" "fake.class.path"]))

(let [lock (ReentrantLock.)]
  (defn with-classpath-cache*
    [key value-fn]
    (.lock lock)
    (try (let [cache @primitive-cache
               cp-hash (reduce hash-combine 0 (classpath-strings))
               same-cp? (= cp-hash (:compliment.lite/classpath-hash cache))
               cached-value (cache key)]
           (if (and (some? cached-value) same-cp?)
             cached-value
             (let [value (value-fn)]
               (reset! primitive-cache (assoc (if same-cp?
                                                cache
                                                {:compliment.lite/classpath-hash cp-hash})
                                              key
                                              value))
               value)))
         (finally (.unlock lock)))))

(defmacro with-classpath-cache
  "If cache for `name` is absent, or `key` doesn't match the key in the cache,\n  calculate `v` and return it. Else return value from cache."
  {:style/indent 1}
  [key value]
  `(with-classpath-cache* ~key (fn [] ~value)))

(defn- classpath
  "Returns a sequence of File objects of the elements on the classpath."
  []
  (mapcat #(.split ^String % File/pathSeparator) (classpath-strings)))

(defn- file-seq-nonr
  "A tree seq on java.io.Files, doesn't resolve symlinked directories to avoid\n  infinite sequence resulting from recursive symlinked directories."
  [dir]
  (tree-seq (fn [^File f] (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f)))))
            (fn [^File d] (seq (.listFiles d)))
            dir))

(defn- list-files
  "Given a path (either a jar file, directory with classes or directory with\n  paths) returns all files under that path."
  [^String path scan-jars?]
  (cond (.endsWith path "/*") (for [^File jar (.listFiles (File. path))
                                    :when (.endsWith (.getName jar) ".jar")
                                    file (list-files (.getPath jar) scan-jars?)]
                                file)
        (.endsWith path ".jar")
        (if scan-jars?
          (try (-> (.stream (JarFile. path))
                   (.filter (reify
                              Predicate
                              (test [_ entry] (not (.isDirectory ^JarEntry entry)))))
                   (.map (reify
                           Function
                           (apply [_ entry] (.getName ^JarEntry entry))))
                   (.collect (Collectors/toList)))
               (catch Exception _))
          ())
        (= path "") ()
        (.exists (File. path))
        (let [root (File. path)
              root-path (.toPath root)]
          (for [^File file (file-seq-nonr root)
                :when (not (.isDirectory file))]
            (let [filename (str (.relativize root-path (.toPath file)))]
              (cond-> filename
                (not= File/separator "/") (.replace File/separator "/")
                (.startsWith filename "/") (.substring filename 1)))))))

(defmacro list-jdk9-base-classfiles
  "Because on JDK9+ the classfiles are stored not in rt.jar on classpath, but in\n  modules, we have to do extra work to extract them."
  []
  (when (resolve-class *ns* 'java.lang.module.ModuleFinder)
    `(-> (.findAll (java.lang.module.ModuleFinder/ofSystem))
         (.stream)
         (.flatMap (reify Function
                     (apply [_ mref#]
                       (.list (.open ^java.lang.module.ModuleReference mref#)))))
         (.collect (Collectors/toList)))))

(defn- all-files-on-classpath*
  "Given a list of files on the classpath, returns the list of all files,\n  including those located inside jar files."
  [classpath]
  (let [seen (java.util.HashMap.)
        seen? (fn [x] (.putIfAbsent seen x x))]
    (-> []
        (into (comp (map #(list-files % true)) cat (remove seen?)) classpath)
        (into (remove seen?) (list-jdk9-base-classfiles)))))

(defn- classes-on-classpath*
  [files]
  (let [roots (volatile! #{})
        filename->classname
        (fn [^String file]
          (when (.endsWith file ".class")
            (when-not (or (.contains file "__")
                          (.contains file "$")
                          (.equals file "module-info.class"))
              (let [c (-> (subs file 0 (- (.length file) 6))
                          (.replace "/" "."))]
                (vswap! roots conj (subs c 0 (max (.indexOf ^String c ".") 0)))
                c))))
        classes (into [] (keep filename->classname) files)
        roots (set (remove empty? @roots))]
    [classes roots]))

(defn namespaces-on-classpath*
  [files]
  (into []
        (keep (fn [^String file]
                (when (or (.endsWith file ".clj")
                          (.endsWith file ".cljs")
                          (.endsWith file ".cljc"))
                  (let [ns-str (.. (subs file 0 (.lastIndexOf file "."))
                                   (replace "/" ".")
                                   (replace "_" "-"))]
                    {:ns-str ns-str, :file file}))))
        files))

(defn- recache-files-on-classpath
  []
  (with-classpath-cache :files-on-classpath
    (let [files (all-files-on-classpath* (classpath))
          [classes roots] (classes-on-classpath* files)]
      {:classes classes,
       :root-packages roots,
       :namespaces (namespaces-on-classpath* files)})))

(defn classes-on-classpath
  "Returns a map of all classes that can be located on the classpath. Key\n  represent the root package of the class, and value is a list of all classes\n  for that package."
  []
  (:classes (recache-files-on-classpath)))

(defn root-packages-on-classpath
  "Return a set of all classname \"TLDs\" on the classpath."
  []
  (:root-packages (recache-files-on-classpath)))

(defn namespaces&files-on-classpath
  "Returns a collection of maps (e.g. `{:ns-str \"some.ns\", :file\n  \"some/ns.cljs\"}`) of all clj/cljc/cljs namespaces obtained by classpath\n  scanning."
  []
  (:namespaces (recache-files-on-classpath)))

;; compliment/sources.clj

(def ^{:private true} sources "Stores defined sources." (atom {}))

(defn all-sources
  "Returns the list of all completion sources, or the selected once specified by\n  `source-kws`."
  ([] @sources)
  ([source-kws] (select-keys @sources source-kws)))

(defn defsource
  "Define a source with the given name and completion functions:\n  `:candidates` - a function of prefix, namespace and context;\n  `:doc` - a function of symbol name and namespace."
  [name & {:keys [candidates doc], :as kw-args}]
  {:pre [candidates]}
  (swap! sources assoc name (assoc kw-args :enabled true)))

;; compliment/sources/class_members.clj

(defn- clojure-1-12+?
  []
  (or (> (:major *clojure-version*) 1) (>= (:minor *clojure-version*) 12)))

(defn static?
  "Tests if class member is static."
  [^Member member]
  (Modifier/isStatic (.getModifiers member)))

(def global-members-cache
  "Stores cache of all non-static members for every namespace."
  (atom {}))

(defn- demunge-deftype-field-name
  "If the member is a deftype field, change .x_y to .x-y for compatibility. See\n  https://github.com/alexander-yakushev/compliment/issues/33."
  [^Member m ^Class c ^String name]
  (if (and (instance? Field m) (.isAssignableFrom clojure.lang.IType c))
    (.replaceAll name "_" "-")
    name))

(defn populate-global-members-cache
  "Populate members cache of class members for `ns` from the given set of classes."
  [ns classes]
  (let [members (for [^Class class classes
                      ^Member member (concat (.getMethods class) (.getFields class))
                      :when (not (static? member))]
                  (let [dc (.getDeclaringClass member)
                        name (.getName member)
                        demunged-name (demunge-deftype-field-name member dc name)]
                    [demunged-name
                     (if (= dc class)
                       member
                       (if (instance? Method member)
                         (.getMethod dc name (.getParameterTypes ^Method member))
                         (.getField dc name)))]))
        cache (reduce (fn [cache [full-name m]]
                        (assoc! cache full-name (conj (cache full-name []) m)))
                      (transient {})
                      members)]
    (swap! global-members-cache assoc
           ns
           {:classes (set classes), :members (persistent! cache)})))

(defn update-global-cache
  "Updates members cache for a given namespace if necessary."
  [ns]
  (let [imported-classes (reduce-kv (fn [acc _ mapping]
                                      (if (class? mapping) (conj acc mapping) acc))
                                    #{}
                                    (ns-map ns))
        cache (@global-members-cache ns)]
    (when (or (nil? cache) (not= (count (:classes cache)) (count imported-classes)))
      (populate-global-members-cache ns imported-classes))))

(defn get-all-members
  "Returns all non-static members for a given namespace."
  [ns]
  (update-global-cache ns)
  (get-in @global-members-cache [ns :members]))

(def ^{:private true} class-members-cache "Members by class" (atom {}))

(defn- populate-class-members-cache
  "Populates qualified methods cache for a given class."
  [^Class class]
  (swap! class-members-cache assoc
         class
         (let [methods&fields (concat (.getMethods class) (.getFields class))
               constructors (.getConstructors class)
               member->type #(if (instance? Field %)
                               (if (static? %) :static-field :field)
                               (if (static? %) :static-method :method))
               update-cache (fn [cache member name type]
                              (update cache
                                      name
                                      (fn [members]
                                        (vary-meta (-> (or members [])
                                                       (conj member))
                                                   update
                                                   :types
                                                   (fnil conj #{})
                                                   type))))
               cache (reduce (fn [cache ^Member m]
                               (update-cache cache m (.getName m) (member->type m)))
                             {}
                             methods&fields)]
           (reduce (fn [cache ^Member m] (update-cache cache m "new" :constructor))
                   cache
                   constructors))))

(defn- get-all-class-members
  [klass]
  (or (@class-members-cache klass) (get (populate-class-members-cache klass) klass)))

(defn qualified-member-symbol?
  [x]
  (some-> (re-matches #"([^\/\:\.][^\:]*)(?<!\.)\/(.*)" (str x))
          (subvec 1)))

(defn class-member-symbol?
  "Tests if `x` looks like a non-static class member,\n  e.g. \".getMonth\" or \"java.util.Date/.getMonth\" (for Clojure v1.12+).\n\n  When true, yields `[klass-name method-name]`."
  [x]
  (cond (str/starts-with? x ".") [nil x]
        (clojure-1-12+?)
        (when-let [[_klass method :as parts] (qualified-member-symbol? x)]
          (when (or (empty? method) (str/starts-with? method ".")) parts))))

(defn camel-case-matches?
  "Tests if prefix matches the member name following camel case rules.\n  Thus, prefix `getDeF` matches member `getDeclaredFields`."
  [prefix member-name]
  (fuzzy-matches-no-skip? prefix member-name (fn [ch] (Character/isUpperCase ^char ch))))

(defn members-candidates
  "Returns a list of Java non-static fields and methods candidates."
  [prefix ns context]
  (when-let [[klass-name prefix] (class-member-symbol? prefix)]
    (let [prefix (str/replace prefix #"^\." "")
          qualified? (some? klass-name)
          inparts? (re-find #"[A-Z]" prefix)
          klass (if qualified? (resolve-class ns (symbol klass-name)) nil)
          exact-class? (or qualified? klass)]
      (for [[member-name members] (if exact-class?
                                    (some-> klass
                                            get-all-class-members)
                                    (get-all-members ns))
            :when (and (if inparts?
                         (camel-case-matches? prefix member-name)
                         (.startsWith ^String member-name prefix))
                       (if exact-class?
                         (some (:types (meta members)) [:method :field])
                         true))]
        {:candidate
         (if qualified? (str klass-name "/." member-name) (str "." member-name)),
         :type (cond exact-class? (first (:types (meta members)))
                     (instance? Method (first members)) :method
                     :else :field),
         :priority 0}))))

(defn static-member-symbol?
  "Tests if prefix looks like a static member symbol, returns parsed parts."
  [x]
  (when-let [[_klass method :as parts] (qualified-member-symbol? x)]
    (when-not (str/starts-with? (str method) ".") parts)))

(defn static-members-candidates
  "Returns a list of static member candidates."
  [^String prefix ns _context]
  (when-let [[cl-name member-prefix] (static-member-symbol? prefix)]
    (when-let [cl (resolve-class ns (symbol cl-name))]
      (let [inparts? (re-find #"[A-Z]" member-prefix)]
        (for [[^String member-name members] (get-all-class-members cl)
              :let [types (:types (meta members))]
              :when (and (if inparts?
                           (camel-case-matches? member-prefix member-name)
                           (.startsWith member-name member-prefix))
                         (or (some #{:static-method :static-field} types)
                             (and (clojure-1-12+?) (:constructor types))))]
          {:candidate (str cl-name "/" member-name),
           :type (cond (instance? Constructor (first members)) :constructor
                       (instance? Method (first members)) :static-method
                       :else :static-field),
           :priority 0})))))

(defsource :compliment.lite/members :candidates #'members-candidates)

(defsource :compliment.lite/static-members :candidates #'static-members-candidates)

;; compliment/sources/namespaces.clj

(defn nscl-symbol?
  "Tests if prefix looks like a namespace or classname."
  [^String x]
  (and (re-matches #"[^\/\:]+" x) (not (= (.charAt x 0) \.))))

(defn nscl-matches?
  "Tests if prefix partially matches a namespace or classname with periods as\n  separators."
  [prefix namespace]
  (fuzzy-matches? prefix namespace \.))

(defn namespaces-candidates
  "Return a list of namespace candidates."
  [^String prefix ns context]
  (when (nscl-symbol? prefix)
    (let [has-dot (> (.indexOf prefix ".") -1)
          [literals prefix] (split-by-leading-literals prefix)
          cands-from-cp
          (for [{:keys [^String ns-str file]} (namespaces&files-on-classpath)
                :when (and (re-find #"\.cljc?$" file)
                           (if has-dot
                             (nscl-matches? prefix ns-str)
                             (.startsWith ns-str prefix)))]
            {:candidate (str literals ns-str), :type :namespace, :file file, :priority 0})
          ns-names (set (map :candidate cands-from-cp))
          ns-sym->cand
          #(let [ns-str (name %1)]
             (when (and (nscl-matches? prefix ns-str) (not (ns-names ns-str)))
               {:candidate (str literals ns-str %2), :type :namespace, :priority 0}))]
      (-> cands-from-cp
          (into (keep #(ns-sym->cand % "/")) (keys (ns-aliases ns)))
          (into (comp (map ns-name) (keep #(ns-sym->cand % nil))) (all-ns))))))

(defsource :compliment.lite/namespaces :candidates #'namespaces-candidates)

;; compliment/sources/classes.clj

(defn- all-classes-short-names
  "Returns a map where short classnames are matched with vectors with\n  package-qualified classnames."
  []
  (with-classpath-cache :all-classes-short-names
    (group-by (fn [^String s]
                (.substring s (inc (.lastIndexOf s "."))))
              (classes-on-classpath))))

(defn- get-classes-by-package-name
  "Returns simple classnames that match the `prefix` and belong to `pkg-name`."
  [prefix pkg-name]
  (reduce-kv (fn [l ^String short-name full-names]
               (if (and (.startsWith short-name prefix)
                        (some (fn [^String fn] (.startsWith fn pkg-name)) full-names))
                 (conj l {:candidate short-name, :type :class})
                 l))
             []
             (all-classes-short-names)))

(defn classes-candidates
  "Returns a list of classname completions."
  [^String prefix ns context]
  (when (nscl-symbol? prefix)
    (if-let [import-ctx (get {} :nil)]
      (get-classes-by-package-name prefix import-ctx)
      (let [has-dot (.contains prefix ".")
            seen (HashSet.)
            include? (fn
                       ([class-str remember?]
                        (when-not (.contains seen class-str)
                          (when remember? (.add seen class-str))
                          true)))
            str->cand (fn [s fqname] {:candidate s, :type :class, :priority 0})
            all-classes (classes-on-classpath)
            it (.iterator ^Iterable all-classes)
            roots (root-packages-on-classpath)]
        (as-> (transient []) result
          (reduce-kv (fn [result _ ^Class v]
                       (if (class? v)
                         (let [fqname (.getName v)
                               sname (.getSimpleName v)]
                           (cond-> result
                             (and (nscl-matches? prefix fqname) (include? fqname true))
                             (conj! (str->cand fqname fqname))
                             (and (nscl-matches? prefix sname) (include? sname true))
                             (conj! {:candidate sname,
                                     :type :class,
                                     :package (some-> (.getPackage v)
                                                      .getName),
                                     :priority 0})))
                         result))
                     result
                     (ns-map ns))
          (if (Character/isUpperCase (.charAt prefix 0))
            (reduce-kv (fn [result ^String short-name full-names]
                         (if (.startsWith short-name prefix)
                           (reduce (fn [result cl]
                                     (cond-> result
                                       (include? cl true) (conj! (str->cand cl cl))))
                                   result
                                   full-names)
                           result))
                       result
                       (all-classes-short-names))
            result)
          (if (or has-dot (contains? roots prefix))
            (loop [result result]
              (if (.hasNext it)
                (let [^String cl (.next it)]
                  (recur (cond-> result
                           (and (.startsWith cl prefix) (include? cl false))
                           (conj! (str->cand cl cl)))))
                result))
            (reduce conj!
                    result
                    (for [^String root-pkg roots
                          :when (and (.startsWith root-pkg prefix)
                                     (include? root-pkg false))]
                      (str->cand (str root-pkg ".") ""))))
          (persistent! result))))))

(defsource :compliment.lite/classes :candidates #'classes-candidates)

;; compliment/sources/vars.clj

(defn var-symbol?
  "Test if prefix resembles a var name."
  [x]
  (re-matches #"(?:([^\/\:][^\/]*)\/)?(|[^/:][^/]*)" x))

(defn dash-matches?
  "Tests if prefix partially matches a var name with dashes as\n  separators."
  [prefix var]
  (fuzzy-matches? prefix var \-))

(defn vars-candidates
  "Returns a list of namespace-bound candidates, with namespace being\n  either the scope (if prefix is scoped), `ns` arg or the namespace\n  extracted from context if inside `ns` declaration."
  [^String prefix ns context]
  (let [[literals prefix] (split-by-leading-literals prefix)]
    (when-let [[_ scope-name prefix] (var-symbol? prefix)]
      (let [scope (some-> scope-name
                          symbol
                          (resolve-namespace ns))
            ns-form-namespace (get {} :nil)
            vars (cond scope (if (and literals (re-find #"#'$" literals))
                               (ns-interns scope)
                               (ns-publics scope))
                       (and scope-name (nil? scope)) ()
                       ns-form-namespace (ns-publics ns-form-namespace)
                       :else (ns-map ns))]
        (for [[var-sym var] vars
              :let [var-name (name var-sym)]
              :when (and (var? var) (dash-matches? prefix var-name))
              :let [var-meta (meta var)]
              :when (not (:completion/hidden var-meta))
              :let [var-ns (.ns ^clojure.lang.Var var)
                    this-ns? (= var-ns ns)
                    clojure-ns? true]]
          (cond-> {:candidate (str literals
                                   (if scope (str scope-name "/" var-name) var-name)),
                   :type (cond (:macro var-meta) :macro
                               (:arglists var-meta) :function
                               :else :var),
                   :ns (str (or (:ns var-meta) ns)),
                   :priority 0}
            *extra-metadata* (identity)))))))

(defsource :compliment.lite/vars :candidates #'vars-candidates)

;; compliment/sources/keywords.clj

(def ^{:private true} keywords-table
  (delay (let [^Field field (.getDeclaredField clojure.lang.Keyword "table")]
           (.setAccessible field true)
           (.get field nil))))

(defn- tagged-candidate [c] {:candidate c, :type :keyword})

(defn qualified-candidates
  "Returns a list of namespace-qualified double-colon keywords (like ::foo)\n  resolved for the given namespace."
  [prefix ns]
  (let [prefix (subs prefix 2)
        ns-name (str ns)]
    (for [[kw _] @keywords-table
          :when (= (namespace kw) ns-name)
          :when (.startsWith (name kw) prefix)]
      (tagged-candidate (str "::" (name kw))))))

(defn namespace-alias-candidates
  "Returns a list of namespace aliases prefixed by double colon required in the\n  given namespace."
  [prefix ns]
  (let [prefix (subs prefix 2)]
    (for [[alias _] (ns-aliases ns)
          :let [aname (name alias)]
          :when (.startsWith aname prefix)]
      (tagged-candidate (str "::" aname "/")))))

(defn aliased-candidates
  "Returns a list of alias-qualified double-colon keywords (like ::str/foo),\n  where alias has to be registered in the given namespace."
  [prefix ns]
  (when-let [[_ alias prefix] (re-matches #"::([^/]+)/(.*)" prefix)]
    (let [alias-ns-name (str (resolve-namespace (symbol alias) ns))]
      (for [[kw _] @keywords-table
            :when (= (namespace kw) alias-ns-name)
            :when (.startsWith (name kw) prefix)]
        (tagged-candidate (str "::" alias "/" (name kw)))))))

(defn keyword-candidates
  [^String prefix ns _]
  (let [single-colon? (.startsWith prefix ":")
        double-colon? (.startsWith prefix "::")
        has-slash? (> (.indexOf prefix "/") -1)]
    (cond (and double-colon? has-slash?) (aliased-candidates prefix ns)
          double-colon? (concat (qualified-candidates prefix ns)
                                (namespace-alias-candidates prefix ns))
          single-colon? (let [prefix (subs prefix 1)]
                          (for [[kw _] @keywords-table
                                :when (.startsWith (str kw) prefix)]
                            (tagged-candidate (str ":" kw)))))))

(defsource :compliment.lite/keywords :candidates #'keyword-candidates)

;; compliment/sources/special_forms.clj

(def ^{:private true} special-forms
  (set (map name
            '[def if do quote var recur throw try catch monitor-enter monitor-exit new
              set!])))

(defn special-form-candidates
  "Returns list of completions for special forms."
  [prefix _ context]
  (when (var-symbol? prefix)
    (for [form (concat special-forms ["true" "false" "nil"])
          :when (dash-matches? prefix form)]
      {:candidate form, :type :special-form})))

(defsource :compliment.lite/special-forms :candidates #'special-form-candidates)

;; compliment/core.clj

(defn ensure-ns
  "Takes either a namespace object or a symbol and returns the corresponding\n  namespace if it exists, otherwise returns `user` namespace."
  [nspc]
  (or (and (instance? clojure.lang.Namespace nspc) nspc)
      (and (symbol? nspc) (find-ns nspc))
      (find-ns 'user)
      *ns*))

(defn completions
  "Returns a list of completions for the given prefix and namespace."
  ([prefix] (completions prefix *ns* {}))
  ([prefix ns] (completions prefix ns {}))
  ([prefix ns _]
   (let [nspc (ensure-ns ns)
         candidate-fns (keep (fn [[_ src]] (when (:enabled src) (:candidates src)))
                             (all-sources))
         candidates
         (into [] (comp (map (fn [f] (f prefix nspc nil))) cat) candidate-fns)]
     (sort-by :candidate candidates))))
