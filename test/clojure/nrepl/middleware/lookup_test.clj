(ns nrepl.middleware.lookup-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.test-helpers :refer :all]))

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
         (message! op))))

(def-repl-test lookup-op-error
  (is+ {:status #{:done :error :lookup-error}}
       (message! session {:op "lookup"})))

(def-repl-test lookup-op-custom-fn
  (is+ {:status #{:done}, :info {:foo 1 :bar 2}}
       (message! session {:op "lookup" :sym "map" :ns "clojure.core" :lookup-fn "nrepl.middleware.lookup-test/dummy-lookup"})))

(def-repl-test lookup-op-unresolvable-custom-fn
  ;; An unresolvable :lookup-fn falls back to the default rather than failing
  ;; the request, whether its namespace is missing or it isn't qualified.
  (doseq [bad ["my.missing.ns/lookup" "unqualified"]]
    (is+ {:status #{:done}, :info {:name "map"}}
         (message! session {:op "lookup" :sym "map" :ns "clojure.core" :lookup-fn bad}))))
