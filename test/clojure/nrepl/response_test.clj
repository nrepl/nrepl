(ns nrepl.response-test
  (:use clojure.test
        [nrepl.transport :only (piped-transports) :as t])
  (:require [nrepl.core :as repl])
  (:import (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(deftest response-seq
  (let [[local remote] (piped-transports)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) (repl/response-seq local 0)))

    ;; ensure timeouts don't capture later responses
    (repl/response-seq local 100)
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) (repl/response-seq local 0)))))

(deftest client
  (let [[local remote] (piped-transports)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) ((repl/client local 100) 17)))
    (is (= 17 (t/recv remote)))))

(deftest client-heads
  (let [[local remote] (piped-transports)
        client1 (repl/client local Long/MAX_VALUE)
        all-seq (client1)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= [0 1 2] (take 3 all-seq)))
    (is (= (range 3 7) (take 4 (client1 :a))))
    (is (= :a (t/recv remote)))
    (is (= (range 10) (take 10 all-seq)))))
