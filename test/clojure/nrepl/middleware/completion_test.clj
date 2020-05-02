(ns nrepl.middleware.completion-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir clean-response]])
  (:import
   (java.io File)))

(use-fixtures :each repl-server-fixture)

(defn dummy-completion [prefix _ns _options]
  [{:candidate prefix}])

(def-repl-test completions-op
  (let [result (-> (nrepl/message session {:op "completions" :prefix "map" :ns "clojure.core"})
                   nrepl/combine-responses
                   clean-response
                   (select-keys [:completions :status]))]
    (is (= #{:done} (:status result)))
    (is (not-empty (:completions result)))))

(def-repl-test completions-op-error
  (let [result (-> (nrepl/message session {:op "completions"})
                   nrepl/combine-responses
                   clean-response
                   (select-keys [:completions :status]))]
    (is (= #{:done :completion-error} (:status result)))))

(def-repl-test completions-op-custom-fn
  (let [result (-> (nrepl/message session {:op "completions" :prefix "map" :ns "clojure.core" :complete "nrepl.middleware.completion-test/dummy-completion"})
                   nrepl/combine-responses
                   clean-response
                   (select-keys [:completions :status]))]
    (is (= #{:done} (:status result)))
    (is (= [{:candidate "map"}] (:completions result)))))
