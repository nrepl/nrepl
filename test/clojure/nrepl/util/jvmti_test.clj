(ns nrepl.util.jvmti-test
  (:require [nrepl.misc :as misc]
            [clojure.test :refer :all]))

(when (>= misc/java-version 21)
  (deftest stop-thread-test
    (let [vol (volatile! 0)
          t (doto (Thread. #(while (vswap! vol inc))) .start)]
      ((misc/requiring-resolve 'nrepl.util.jvmti/stop-thread) t)
      (Thread/sleep 1000)
      (let [v @vol]
        (Thread/sleep 500)
        (is (= v @vol))
        (is (= Thread$State/TERMINATED (.getState t)))))))
