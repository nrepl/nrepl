(ns nrepl.util.completion
  "Code completion functionality.

  This namespace is based on compliment-lite.
  (https://github.com/alexander-yakushev/compliment/tree/master/lite)

  The functionality here is experimental and the API is subject to changes."
  {:author "Oleksandr Yakushev, Bozhidar Batsov"
   :added  "0.8"}
  (:import java.io.File
           java.nio.file.Files
           (java.util.function Function Predicate)
           java.util.concurrent.locks.ReentrantLock
           (java.util.jar JarEntry JarFile)
           java.util.stream.Collectors
           (java.lang.reflect Field Member Method Modifier)
           (java.util Comparator HashSet)))

;; Compliment lite was generated at Mon May 06 15:50:29 EEST 2024
;; SPDX-License-Identifier: EPL-1.0

;; compliment/utils.clj

(def ^{:dynamic true} *extra-metadata*
  "Signals to downstream sources which additional information about completion\n  candidates they should attach . Should be a set of keywords."
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
          :else
          (loop [pi 1
                 si 1
                 skipping false]
            (cond (>= pi pn) true
                  (>= si sn) false
                  :else
                  (let [pc (.charAt prefix pi)
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
  (when-let [val (try (ns-resolve ns sym)
                      (catch ClassNotFoundException _))]
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
        ["sun.boot.class.path" "java.ext.dirs" "java.class.path"
         "fake.class.path"]))

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
                                                {:compliment.lite/classpath-hash
                                                 cp-hash})
                                              key value))
               value)))
         (finally (.unlock lock)))))

(defmacro with-classpath-cache
  "If cache for `name` is absent, or `key` doesn't match the key in the cache,
  calculate `v` and return it. Else return value from cache."
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
  (tree-seq (fn [^File f]
              (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f)))))
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
                              (test [_ entry]
                                (not (.isDirectory ^JarEntry entry)))))
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
  "Because on JDK9+ the classfiles are stored not in rt.jar on classpath, but in
  modules, we have to do extra work to extract them."
  []
  (if (resolve-class *ns* 'java.lang.module.ModuleFinder)
    `(try (-> (.findAll (java.lang.module.ModuleFinder/ofSystem))
              (.stream)
              (.flatMap (reify Function
                          (apply [_ mref#]
                            (.list (.open ^java.lang.module.ModuleReference mref#)))))
              (.collect (Collectors/toList)))
          ;; Due to a bug in Clojure before 1.10, the above may fail.
          (catch IncompatibleClassChangeError _# []))
    ()))

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
                (vswap! roots
                        conj
                        (subs c 0 (max (.indexOf ^String c ".") 0)))
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
  "Define a source with the given name and completion function:\n  `:candidates` - a function of prefix, namespace and context."
  [name & {:keys [candidates], :as kw-args}]
  {:pre [candidates]}
  (swap! sources assoc name (assoc kw-args :enabled true)))

;; compliment/sources/class_members.clj

(defn static?
  "Tests if class member is static."
  [^Member member]
  (Modifier/isStatic (.getModifiers member)))

(def members-cache
  "Stores cache of all non-static members for every namespace."
  (atom {}))

(defn- demunge-deftype-field-name
  "If the member is a deftype field, change .x_y to .x-y for compatibility. See\n  https://github.com/alexander-yakushev/compliment/issues/33."
  [^Member m ^Class c ^String name]
  (if (and (instance? Field m) (.isAssignableFrom clojure.lang.IType c))
    (.replaceAll name "_" "-")
    name))

(defn populate-members-cache
  "Populate members cache of class members for `ns` from the given list of\n  classes. `imported-classes-cnt` is a number that indicates the current number\n  of imported classes in this namespace."
  [ns classes imported-classes-cnt]
  (let [members
        (for [^Class class classes
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
    (swap! members-cache assoc
           ns
           {:classes (set classes),
            :imported-classes-cnt imported-classes-cnt,
            :members (persistent! cache)})))

(defn update-cache
  "Updates members cache for a given namespace if necessary."
  ([ns] (update-cache ns nil))
  ([ns context-class]
   (let [imported-classes (reduce-kv
                           (fn [acc _ mapping]
                             (if (class? mapping) (conj acc mapping) acc))
                           []
                           (ns-map ns))
         imported-classes-cnt (count imported-classes)
         cache (@members-cache ns)]
     (when (or (nil? cache)
               (not= (:imported-classes-cnt cache) imported-classes-cnt)
               (and context-class
                    (not (contains? (:classes cache) context-class))))
       (let [classes (cond-> (into (set imported-classes) (:classes cache))
                       context-class (conj context-class))]
         (populate-members-cache ns classes imported-classes-cnt))))))

(defn get-all-members
  "Returns all non-static members for a given namespace."
  [ns context-class]
  (update-cache ns context-class)
  (get-in @members-cache [ns :members]))

(defn class-member-symbol?
  "Tests if a symbol name looks like a non-static class member."
  [^String x]
  (.startsWith x "."))

(defn camel-case-matches?
  "Tests if prefix matches the member name following camel case rules.\n  Thus, prefix `getDeF` matches member `getDeclaredFields`."
  [prefix member-name]
  (fuzzy-matches-no-skip? prefix
                          member-name
                          (fn [ch] (Character/isUpperCase ^char ch))))

(defn members-candidates
  "Returns a list of Java non-static fields and methods candidates."
  [prefix ns _]
  (when (class-member-symbol? prefix)
    (let [prefix (subs prefix 1)
          inparts? (re-find #"[A-Z]" prefix)
          klass nil]
      (for [[member-name members] (get-all-members ns klass)
            :when (and (if inparts?
                         (camel-case-matches? prefix member-name)
                         (.startsWith ^String member-name prefix))
                       true)]
        {:candidate (str "." member-name),
         :type (if (instance? Method (first members)) :method :field)}))))

(defsource :compliment.lite/members :candidates #'members-candidates)

(defn static-member-symbol?
  "Tests if prefix looks like a static member symbol, returns parsed parts."
  [x]
  (re-matches #"([^\/\:\.][^\:]*)\/(.*)" x))

(def static-members-cache
  "Stores cache of all static members for every class."
  (atom {}))

(defn populate-static-members-cache
  "Populates static members cache for a given class."
  [^Class class]
  (swap! static-members-cache assoc
         class
         (reduce (fn [cache ^Member c]
                   (if (static? c)
                     (update cache (.getName c) (fnil conj []) c)
                     cache))
                 {}
                 (concat (.getMethods class) (.getFields class)))))

(defn static-members
  "Returns all static members for a given class."
  [^Class class]
  (or (@static-members-cache class)
      (get (populate-static-members-cache class) class)))

(defn static-members-candidates
  "Returns a list of static member candidates."
  [^String prefix ns _]
  (when-let [[_ cl-name member-prefix] (static-member-symbol? prefix)]
    (when-let [cl (resolve-class ns (symbol cl-name))]
      (let [inparts? (re-find #"[A-Z]" member-prefix)]
        (for [[^String member-name members] (static-members cl)
              :when (if inparts?
                      (camel-case-matches? member-prefix member-name)
                      (.startsWith member-name member-prefix))]
          {:candidate (str cl-name "/" member-name),
           :type (if (instance? Method (first members))
                   :static-method
                   :static-field)})))))

(defsource :compliment.lite/static-members
  :candidates
  #'static-members-candidates)

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
  [^String prefix ns _]
  (when (nscl-symbol? prefix)
    (let [has-dot (> (.indexOf prefix ".") -1)
          [literals prefix] (split-by-leading-literals prefix)
          cands-from-classpath
          (for [{:keys [^String ns-str file]} (namespaces&files-on-classpath)
                :when (and (re-find #"\.cljc?$" file)
                           (if has-dot
                             (nscl-matches? prefix ns-str)
                             (.startsWith ns-str prefix)))]
            {:candidate (str literals ns-str), :type :namespace, :file file})
          ns-names (set (map :candidate cands-from-classpath))
          ns-sym->cand #(let [ns-str (name %)]
                          (when (and (nscl-matches? prefix ns-str)
                                     (not (ns-names ns-str)))
                            {:candidate (str literals ns-str),
                             :type :namespace}))]
      (-> cands-from-classpath
          (into (keep ns-sym->cand) (keys (ns-aliases ns)))
          (into (comp (map ns-name) (keep ns-sym->cand)) (all-ns))))))

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
                        (some (fn [^String fn] (.startsWith fn pkg-name))
                              full-names))
                 (conj l {:candidate short-name, :type :class})
                 l))
             []
             (all-classes-short-names)))

(defn classes-candidates
  "Returns a list of classname completions."
  [^String prefix ns _]
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
            str->cand (fn [s] {:candidate s, :type :class})
            all-classes (classes-on-classpath)
            it (.iterator ^Iterable all-classes)
            roots (root-packages-on-classpath)]
        (as-> (transient []) result
          (reduce-kv
           (fn [result _ v]
             (if (class? v)
               (let [fqname (.getName ^Class v)
                     sname (.getSimpleName ^Class v)]
                 (cond-> result
                   (and (nscl-matches? prefix fqname) (include? fqname true))
                   (conj! (str->cand fqname))
                   (and (nscl-matches? prefix sname) (include? sname true))
                   (conj! {:candidate sname,
                           :type :class,
                           :package (when-let [pkg (.getPackage ^Class v)]
                                      (.getName ^Package pkg))})))
               result))
           result
           (ns-map ns))
          (if (Character/isUpperCase (.charAt prefix 0))
            (reduce-kv (fn [result ^String short-name full-names]
                         (if (.startsWith short-name prefix)
                           (reduce (fn [result cl]
                                     (cond-> result
                                       (include? cl true) (conj! (str->cand
                                                                  cl))))
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
                           (conj! (str->cand cl)))))
                result))
            (reduce conj!
                    result
                    (for [^String root-pkg roots
                          :when (and (.startsWith root-pkg prefix)
                                     (include? root-pkg false))]
                      (str->cand (str root-pkg ".")))))
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
  [^String prefix ns _]
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
              :let [{:keys [arglists doc private deprecated], :as var-meta}
                    (meta var)]
              :when (not (:completion/hidden var-meta))]
          (cond-> {:candidate
                   (str literals
                        (if scope (str scope-name "/" var-name) var-name)),
                   :type (cond (:macro var-meta) :macro
                               arglists :function
                               :else :var),
                   :ns (str (or (:ns var-meta) ns))}
            (and private (:private *extra-metadata*)) (assoc :private
                                                             (boolean private))
            (and deprecated (:deprecated *extra-metadata*))
            (assoc :deprecated (boolean deprecated))
            (and arglists (:arglists *extra-metadata*))
            (assoc :arglists (apply list (map pr-str arglists)))
            (and doc (:doc *extra-metadata*)) (assoc :doc doc)))))))

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
      (tagged-candidate (str "::" aname)))))

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
          single-colon? (for [[kw _] @keywords-table
                              :when (.startsWith (str kw) (subs prefix 1))]
                          (tagged-candidate (str ":" kw))))))

(defsource :compliment.lite/keywords :candidates #'keyword-candidates)

;; compliment/sources/special_forms.clj

(def ^{:private true} special-forms
  (set (map name
            '[def if do quote var recur throw try catch monitor-enter monitor-exit
              new set!])))

(defn special-candidates
  "Returns list of completions for special forms."
  [prefix _ _]
  (when (and (var-symbol? prefix) true)
    (for [form special-forms
          :when (dash-matches? prefix form)]
      {:candidate form, :type :special-form})))

(defsource :compliment.lite/special-forms :candidates #'special-candidates)

(defn literal-candidates
  "We define `true`, `false`, and `nil` in a separate source because they are\n  not context-dependent (don't have to be first items in the list)."
  [prefix _ __]
  (for [^String literal ["true" "false" "nil"]
        :when (.startsWith literal prefix)]
    {:candidate literal, :type :special-form}))

(defsource :compliment.lite/literals :candidates #'literal-candidates)

;; compliment/core.clj

(def ^{:private true} by-length-comparator
  "Sorts list of strings by their length first, and then alphabetically if length\n  is equal."
  (reify
    Comparator
    (compare [_ s1 s2]
      (let [res (Integer/compare (.length ^String s1) (.length ^String s2))]
        (if (zero? res) (.compareTo ^String s1 s2) res)))))

(defn ensure-ns
  "Takes either a namespace object or a symbol and returns the corresponding\n  namespace if it exists, otherwise returns `user` namespace."
  [nspc]
  (or (and (instance? clojure.lang.Namespace nspc) nspc)
      (and (symbol? nspc) (find-ns nspc))
      (find-ns 'user)
      *ns*))

(defn completions
  "Returns a list of completions for the given prefix.

  Options map can contain the following options:
  - :ns - namespace where completion is initiated;
  - :sort-order (either :by-length or :by-name);
  - :extra-metadata - set of extra fields to add to the maps;
  - :sources - list of source keywords to use."
  ([prefix] (completions prefix *ns* {}))
  ([prefix ns] (completions prefix ns {}))
  ([prefix ns
    {:keys [sort-order sources extra-metadata],
     :or {sort-order :by-name}}]
   (let [nspc (ensure-ns ns)
         ctx nil]
     (binding [*extra-metadata* extra-metadata]
       (let [candidate-fns
             (keep (fn [[_ src]] (when (:enabled src) (:candidates src)))
                   (if sources (all-sources sources) (all-sources)))
             candidates (into []
                              (comp (map (fn [f] (f prefix nspc ctx))) cat)
                              candidate-fns)
             sorted-cands
             (if (= sort-order :by-name)
               (sort-by :candidate candidates)
               (sort-by :candidate by-length-comparator candidates))]
         sorted-cands)))))
