(ns nrepl.middleware.lookup-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir clean-response]])
  (:import
   (java.io File)))

(use-fixtures :each repl-server-fixture)

(defn dummy-lookup [ns sym]
  {:foo 1
   :bar 2})

(def-repl-test lookup-op
  (doseq [op [{:op "lookup" :sym "map" :ns "clojure.core"}
              {:op "lookup" :sym "let" :ns "clojure.core"}
              {:op "lookup" :sym "map" :ns "nrepl.core"}
              {:op "lookup" :sym "future" :ns "nrepl.core"}]]
    (let [result (-> (nrepl/message session op)
                     nrepl/combine-responses
                     clean-response)]
      (is (= #{:done} (:status result)))
      (is (not-empty (:info result))))))

(def-repl-test lookup-op-error
  (let [result (-> (nrepl/message session {:op "lookup"})
                   nrepl/combine-responses
                   clean-response)]
    (is (= #{:done :lookup-error} (:status result)))))

(def-repl-test lookup-op-custom-fn
  (let [result (-> (nrepl/message session {:op "lookup" :sym "map" :ns "clojure.core" :lookup-fn "nrepl.middleware.lookup-test/dummy-lookup"})
                   nrepl/combine-responses
                   clean-response)]
    (is (= #{:done} (:status result)))
    (is (= {:foo 1 :bar 2} (:info result)))))
