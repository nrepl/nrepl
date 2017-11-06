(defproject com.cemerick/nrepl "TESTING-DONTUSE"
  :min-lein-version "2.6.1"
  :resource-paths ["src/main/resources"]
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :test-paths ["src/test/clojure"]

  :dependencies [[org.clojure/clojure "1.8.0"]]
)

(require '[leiningen.core.project :as p])
(swap! p/default-profiles update-in [:base :dependencies]
  #(->> %
     (remove (fn [[group-artifact]] (= 'org.clojure/tools.nrepl group-artifact)))
     vec))

