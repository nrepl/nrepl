(ns nrepl.util.out-test
  (:require [clojure.test :refer :all]
            [nrepl.util.out :as out]))

(defn- reset-callbacks-fixture
  "Helper to reset the internal state for testing."
  [f]
  (reset! @#'out/callbacks {:out {}, :err {}})
  (f))

(use-fixtures :each reset-callbacks-fixture)

(deftest test-wrap-standard-streams-idempotency
  (testing "wrap-standard-streams is idempotent"
    (let [original-out (:out @@#'out/original-print-streams)
          original-err (:err @@#'out/original-print-streams)]
      ;; First call should wrap streams
      (out/wrap-standard-streams)
      (let [first-out System/out
            first-err System/err]
        (is (not= original-out first-out))
        (is (not= original-err first-err))

        ;; Second call should not change streams again
        (out/wrap-standard-streams)
        (is (identical? first-out System/out))
        (is (identical? first-err System/err))))))

(deftest test-output-interception
  (testing "callbacks are invoked when output is written"
    (out/wrap-standard-streams)
    (let [out-calls (atom [])
          err-calls (atom [])]
      (out/set-callback :out :test1 #(swap! out-calls conj [:test1 %]))
      (out/set-callback :out :test2 #(swap! out-calls conj [:test2 %]))
      (out/set-callback :err :test3 #(swap! err-calls conj [:test3 %]))

      ;; Write to streams
      (.println System/out "Hello stdout")
      (.println System/err "Hello stderr")
      (Thread/sleep 100)

      (is (= #{[:test1 "Hello stdout\n"] [:test2 "Hello stdout\n"]}
             (set @out-calls)))
      (is (= [[:test3 "Hello stderr\n"]]
             @err-calls)))))

(deftest test-root-binding-updates
  (testing "*out* and *err* root bindings are updated to point to wrapped streams"
    (out/wrap-standard-streams)
    (let [out-calls (atom [])
          err-calls (atom [])]
      (out/set-callback :out :test1 #(swap! out-calls conj [:test1 %]))
      (out/set-callback :out :test2 #(swap! out-calls conj [:test2 %]))
      (out/set-callback :err :test3 #(swap! err-calls conj [:test3 %]))

      ;; Write to streams
      (.start (Thread. #(println "Hello stdout")))
      (.start (Thread. #(binding [*out* *err*]
                          (println "Hello stderr"))))
      (Thread/sleep 100)

      (is (= #{[:test1 "Hello stdout\n"] [:test2 "Hello stdout\n"]}
             (set @out-calls)))
      (is (= [[:test3 "Hello stderr\n"]]
             @err-calls)))))

(deftest test-error-handling-in-callbacks
  (testing "exceptions in one callback shouldn't break others"
    (out/wrap-standard-streams)
    (let [out-calls (atom [])]
      (out/set-callback :out ::bad-callback #(/ 1 %))
      (out/set-callback :out ::good-callback #(swap! out-calls conj %))

      (dotimes [i 10]
        (.println System/out "1"))
      (Thread/sleep 100)

      (is (= (repeat 10 "1\n") @out-calls)))))
