(ns nrepl.describe-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer [is testing use-fixtures]]
   [matcher-combinators.matchers :as mc]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture]]
   [nrepl.middleware :as middleware]
   [nrepl.server :as server]
   [nrepl.test-helpers :refer [is+]]
   [nrepl.version :as version]))

(use-fixtures :once repl-server-fixture)

(def-repl-test simple-describe
  (is+ {:versions {:nrepl (#'middleware/safe-version version/version)
                   :clojure (assoc (#'middleware/safe-version *clojure-version*)
                                   :version-string (clojure-version))
                   :java {:version-string (System/getProperty "java.version")}}
        :ops (fn [ops]
               (and (= server/built-in-ops (set (map name (keys ops))))
                    (every? empty? (vals ops))))}
       (nrepl/combine-responses
        (nrepl/message timeout-client {:op "describe"}))))

(def-repl-test verbose-describe
  (is+ {:ops (fn [ops]
               (and (= server/built-in-ops (set (map name (keys ops))))
                    (every? #(seq (:doc %)) (vals ops))))
        :aux {:current-ns "user"}}
       (nrepl/combine-responses
        (nrepl/message timeout-client
                       {:op "describe" :verbose? "true"}))))
