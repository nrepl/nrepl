(ns nrepl.middleware.lookup-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir clean-response]]
   [nrepl.test-helpers :refer [is+]])
  (:import
   (java.io File)))

(use-fixtures :each repl-server-fixture)

(defn dummy-lookup [ns sym]
  {:foo 1
   :bar 2})

(defprotocol MyProtocol
  (protocol-method [_]))

(defn fn-with-coll-in-arglist
  [{{bar :bar} :baz}]
  bar)

(def-repl-test lookup-op
  (doseq [op [{:op "lookup" :sym "map" :ns "clojure.core"}
              {:op "lookup" :sym "let" :ns "clojure.core"}
              {:op "lookup" :sym "*assert*" :ns "clojure.core"}
              {:op "lookup" :sym "map" :ns "nrepl.core"}
              {:op "lookup" :sym "future" :ns "nrepl.core"}
              {:op "lookup" :sym "protocol-method" :ns "nrepl.middleware.lookup-test"}
              {:op "lookup" :sym "fn-with-coll-in-arglist" :ns "nrepl.middleware.lookup-test"}]]
    (is+ {:status #{:done}, :info not-empty}
         (-> (nrepl/message session op)
             nrepl/combine-responses
             clean-response))))

(def-repl-test lookup-op-error
  (is+ {:status #{:done :lookup-error :namespace-not-found}}
       (-> (nrepl/message session {:op "lookup"})
           nrepl/combine-responses
           clean-response)))

(def-repl-test lookup-op-custom-fn
  (is+ {:status #{:done}, :info {:foo 1 :bar 2}}
       (-> (nrepl/message session {:op "lookup" :sym "map" :ns "clojure.core" :lookup-fn "nrepl.middleware.lookup-test/dummy-lookup"})
           nrepl/combine-responses
           clean-response)))
