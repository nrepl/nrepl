(ns nrepl.describe-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer [is testing use-fixtures]]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture]]
   [nrepl.middleware :as middleware]
   [nrepl.server :as server]
   [nrepl.version :as version]))

(use-fixtures :once repl-server-fixture)

(def-repl-test simple-describe
  (let [{{:keys [nrepl clojure java]} :versions
         ops :ops} (nrepl/combine-responses
                    (nrepl/message timeout-client {:op "describe"}))]
    (testing "versions"
      (is (= (#'middleware/safe-version version/version) nrepl))
      (is (= (#'middleware/safe-version *clojure-version*) (dissoc clojure :version-string)))
      (is (= (clojure-version) (:version-string clojure)))
      (is (= (System/getProperty "java.version") (:version-string java))))

    (is (= server/built-in-ops (set (map name (keys ops)))))
    (is (every? empty? (map val ops)))))

(def-repl-test verbose-describe
  (let [{:keys [ops aux]} (nrepl/combine-responses
                           (nrepl/message timeout-client
                                          {:op "describe" :verbose? "true"}))]
    (is (= server/built-in-ops (set (map name (keys ops)))))
    (is (every? seq (map (comp :doc val) ops)))
    (is (= {:current-ns "user"} aux))))
