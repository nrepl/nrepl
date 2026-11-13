(ns nrepl.util.jvmti-test
  (:require [nrepl.misc :as misc]
            [nrepl.test-helpers :refer [wait-for win?]]
            [clojure.test :refer :all]))

(when (and (>= misc/java-version 21) (not win?))
  (deftest stop-thread-test
    (let [vol (volatile! 0)
          t (doto (Thread. #(while (vswap! vol inc))) .start)]
      ((requiring-resolve 'nrepl.util.jvmti/stop-thread) t)
      (is (wait-for #(= Thread$State/TERMINATED (.getState t))))
      (let [v @vol]
        (Thread/sleep 200)
        (is (= v @vol))))))
