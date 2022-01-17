(ns nrepl.interruptible-eval-test
  (:require
   [nrepl.middleware.interruptible-eval :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest source-logging-pushback-reader-test
  (testing "Doesn't throw exceptions, see #263"

    (is (sut/source-logging-pushback-reader "Any string" 1 1))

    (testing "Line and column can be nil"
      (is (sut/source-logging-pushback-reader "Any string" nil nil)))))
