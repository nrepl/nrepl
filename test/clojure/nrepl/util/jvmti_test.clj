(ns nrepl.util.jvmti-test
  (:require [nrepl.misc :as misc]
            [clojure.test :refer :all]))

(deftest ^{:min-java-version "11.0"} stop-thread-test
  (let [vol (volatile! 0)
        t (doto (Thread. #(while (vswap! vol inc))) .start)]
    ((misc/requiring-resolve 'nrepl.util.jvmti/stop-thread) t)
    (Thread/sleep 1000)
    (let [v @vol]
      (Thread/sleep 1000)
      (is (= v @vol))
      (is (= "TERMINATED" (str (.getState t)))))))
