(ns nrepl.describe-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir]]
   [nrepl.middleware :as middleware]
   [nrepl.version :as version]))

(use-fixtures :once repl-server-fixture)

(def ^{:private true} op-names
  #{"load-file" "ls-sessions" "interrupt" "stdin"
    "describe" "eval" "close" "clone" "completions"
    "sideloader-start" "sideloader-provide"
    "add-middleware"})

(def-repl-test simple-describe
  (let [{{:keys [nrepl clojure java]} :versions
         ops :ops} (nrepl/combine-responses
                    (nrepl/message timeout-client {:op "describe"}))]
    (testing "versions"
      (when-not (every? #(contains? java %) [:major :minor :incremental :update])
        (println "Got less information out of `java.version` than we'd like:"
                 (System/getProperty "java.version") "=>" java))
      (is (= (#'middleware/safe-version version/version) nrepl))
      (is (= (#'middleware/safe-version *clojure-version*) (dissoc clojure :version-string)))
      (is (= (clojure-version) (:version-string clojure)))
      (is (= (System/getProperty "java.version") (:version-string java))))

    (is (= op-names (set (map name (keys ops)))))
    (is (every? empty? (map val ops)))))

(def-repl-test verbose-describe
  (let [{:keys [ops aux]} (nrepl/combine-responses
                           (nrepl/message timeout-client
                                          {:op "describe" :verbose? "true"}))]
    (is (= op-names (set (map name (keys ops)))))
    (is (every? seq (map (comp :doc val) ops)))
    (is (= {:current-ns "user"} aux))))
