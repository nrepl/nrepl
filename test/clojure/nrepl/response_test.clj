(ns nrepl.response-test
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.transport :as t])
  (:import
   (java.util.concurrent BlockingQueue CountDownLatch LinkedBlockingQueue TimeUnit)))

(deftest response-seq
  (let [[local remote] (t/piped-transports)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) (nrepl/response-seq local 0)))

    ;; ensure timeouts don't capture later responses
    (nrepl/response-seq local 100)
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) (nrepl/response-seq local 0)))))

(deftest client
  (let [[local remote] (t/piped-transports)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) ((nrepl/client local 100) 17)))
    (is (= 17 (t/recv remote)))))

(deftest client-heads
  (let [[local remote] (t/piped-transports)
        client1 (nrepl/client local Long/MAX_VALUE)
        all-seq (client1)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= [0 1 2] (take 3 all-seq)))
    (is (= (range 3 7) (take 4 (client1 :a))))
    (is (= :a (t/recv remote)))
    (is (= (range 10) (take 10 all-seq)))))

(deftest client-hands-out-a-single-response-seq
  ;; Concurrent first calls used to race on the head: each could start its own
  ;; response seq off the transport, and the responses then got split between
  ;; the two, so whoever waited on the wrong one waited forever.
  ;; The heads are compared by identity only: realizing them would block on
  ;; the transport, and a failure report that printed them would do just that.
  (let [[local _remote] (t/piped-transports)
        distinct-heads (fn [client]
                         (let [latch (CountDownLatch. 1)
                               calls (mapv (fn [_] (future (.await latch) (client))) (range 8))]
                           (.countDown latch)
                           (count (into #{} (map #(System/identityHashCode @%)) calls))))]
    (dotimes [_ 1000]
      (is (= 1 (distinct-heads (nrepl/client local 1000)))))))
