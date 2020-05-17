(ns nrepl.transport-test
  (:require [nrepl.transport :as sut]
            [clojure.test :refer [deftest testing is]])
  (:import [java.io ByteArrayOutputStream]))

(deftest bencode-safe-write-test
  (testing "safe-write-bencode only writes if the whole message is writable"
    (let [out (ByteArrayOutputStream.)]
      (is (thrown? IllegalArgumentException
                   (#'sut/safe-write-bencode out {"obj" (Object.)})))
      (is (empty? (.toByteArray out))))))
