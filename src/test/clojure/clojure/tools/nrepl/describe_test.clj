(ns ^{:author "Chas Emerick"}
  clojure.tools.nrepl.describe-test
  (:use [clojure.tools.nrepl-test :only (def-repl-test repl-server-fixture)]
        clojure.test)
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.middleware :as middleware]))

(use-fixtures :once repl-server-fixture)

(def ^{:private true} op-names
  #{:load-file :ls-sessions :interrupt :stdin
    :describe :eval :close :clone})

(def-repl-test simple-describe
  (let [{{:keys [nrepl clojure]} :versions
         ops :ops} (nrepl/combine-responses
                     (nrepl/message timeout-client {:op "describe"}))]
    (testing "versions"
             (is (= (#'middleware/safe-version clojure.tools.nrepl/version) nrepl))
             (is (= (#'middleware/safe-version *clojure-version*) clojure)))
    
    (is (= op-names (set (keys ops))))
    (is (every? empty? (map val ops)))))

(def-repl-test verbose-describe
  (let [{:keys [ops]} (nrepl/combine-responses
                        (nrepl/message timeout-client {:op "describe" :verbose? "true"}))]
    (is (= op-names (set (keys ops))))
    (is (every? seq (map (comp :doc val) ops)))))
