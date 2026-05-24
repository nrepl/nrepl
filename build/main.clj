(ns build.main
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.java.process :as p]
            [clojure.tools.build.api :as b]
            [clojure.tools.build.tasks.write-pom]
            [deps-deploy.deps-deploy :as dd]))

(defn default-opts [{:keys [version] :or {version "99.99"}}]
  (let [lib 'nrepl/nrepl
        target "target"]
    {;; Pom section
     :lib lib
     :version version
     :scm {:url "https://github.com/nrepl/nrepl", :tag version}
     :pom-data [[:description "nREPL is a Clojure *n*etwork REPL."]
                [:url "https://nrepl.org"]
                [:licenses
                 [:license
                  [:name "Eclipse Public License"]
                  [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]

     ;; Build section
     :basis (b/create-basis {})
     :target target
     :class-dir (str target "/classes")
     :jar-file (some->> version (format "%s/%s-%s.jar" target (name lib) version))}))

(defmacro defcmd [name args & body]
  (assert (= (count args) 1))
  `(defn ~name [~'opts]
     (let [~(first args) (merge (default-opts ~'opts) ~'opts)]
       ~@body)))

(defn log [fmt & args] (println (apply format fmt args)))

(defcmd clean [opts] (b/delete {:path (:target opts)}))

(defcmd javac [{:keys [with-tests] :as opts}]
  (b/javac (assoc opts
                  :src-dirs (if with-tests
                              ["src/java" "test/java"]
                              ["src/java"])
                  :javac-opts ["-source" "8" "-target" "8"])))

;; Hack to propagate scope into pom.
(alter-var-root
 #'clojure.tools.build.tasks.write-pom/to-dep
 (fn [f]
   (fn [[_ {:keys [mvn/scope]} :as arg]]
     (let [res (f arg)
           alias (some-> res first namespace)]
       (cond-> res
         (and alias scope) (conj [(keyword alias "scope") scope]))))))

(defcmd jar [{:keys [class-dir basis jar-file] :as opts}]
  (assert (:version opts))
  (doto opts clean javac b/write-pom)
  (log "Building %s..." jar-file)
  (b/copy-dir {:src-dirs   (:paths basis)
               :target-dir class-dir
               :include    "**"})
  (b/jar opts))

(defcmd deploy [{:keys [version jar-file] :as opts}]
  (assert (some->> version (re-matches #"\d+\.\d+\.\d+.*")) (str version))
  (jar opts)
  (log "Deploying %s to Clojars..." version)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path opts)}))

(defcmd install [{:keys [version] :as opts}]
  (jar opts)
  (log "Installing %s to local Maven repository..." version)
  (b/install opts))

(defcmd verify-cljdoc [{:keys [path]}]
  (spit "/tmp/verify-cljdoc-edn" (slurp "https://raw.githubusercontent.com/cljdoc/cljdoc/master/script/verify-cljdoc-edn"))
  (p/exec {:out :inherit, :err :inherit}
          "bash" "/tmp/verify-cljdoc-edn" "doc/cljdoc.edn"))

;; To recompile Java class at runtime:
;; ((requiring-resolve 'virgil/compile-java) ["src"])
