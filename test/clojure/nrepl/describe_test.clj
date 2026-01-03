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

(def built-in-ops
  (->> (conj server/default-middleware #'middleware/wrap-describe)
       (map #(-> % meta :nrepl.middleware/descriptor :handles keys))
       (reduce concat)
       set))

(def-repl-test simple-describe
  (is+ {:versions {:nrepl (#'middleware/safe-version version/version)
                   :clojure (assoc (#'middleware/safe-version *clojure-version*)
                                   :version-string (clojure-version))
                   :java {:version-string (System/getProperty "java.version")}}
        :ops (mc/all-of (mc/via #(set (map name (keys %))) built-in-ops)
                        (fn [ops]
                          (every? empty? (vals ops))))
        :middleware ["#'nrepl.middleware/wrap-describe"
                     "#'nrepl.middleware.completion/wrap-completion"
                     "#'nrepl.middleware.interruptible-eval/interruptible-eval"
                     "#'nrepl.middleware.io/wrap-out"
                     "#'nrepl.middleware.load-file/wrap-load-file"
                     "#'nrepl.middleware.caught/wrap-caught"
                     "#'nrepl.middleware.lookup/wrap-lookup"
                     "#'nrepl.middleware.print/wrap-print"
                     "#'nrepl.middleware.session/add-stdin"
                     "#'nrepl.middleware.session/session"]}
       (nrepl/combine-responses
        (nrepl/message timeout-client {:op "describe"}))))

(def-repl-test verbose-describe
  (is+ {:ops (mc/all-of
              (mc/via #(set (map name (keys %))) built-in-ops)
              (fn [ops] (every? #(seq (:doc %)) (vals ops))))
        :aux {:current-ns "user"}}
       (nrepl/combine-responses
        (nrepl/message timeout-client
                       {:op "describe" :verbose? "true"}))))
