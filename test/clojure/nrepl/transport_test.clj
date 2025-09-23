(ns nrepl.transport-test
  (:require [nrepl.transport :as sut]
            [clojure.test :refer [deftest testing is]])
  (:import (java.io BufferedInputStream BufferedOutputStream
                    ByteArrayInputStream ByteArrayOutputStream)))

(deftest bencode-safe-write-test
  (testing "safe-write-bencode only writes if the whole message is writable"
    (let [out (ByteArrayOutputStream.)]
      (is (thrown? IllegalArgumentException
                   (#'sut/safe-write-bencode out {"obj" (Object.)})))
      (is (empty? (.toByteArray out))))))

(deftest malformed-bencode-input-test
  (testing "if non-bencode input is passed, throw an informative error"
    (let [in (-> (.getBytes "123456789123456789123456789")
                 ByteArrayInputStream.
                 BufferedInputStream.)
          out (BufferedOutputStream. (ByteArrayOutputStream.))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"^nREPL message must be a map."
                            (sut/recv (sut/bencode in out))))))

  (testing "if top-level message is not a map, throw an informative error"
    (let [in (-> (.getBytes "li1ei2ei3ee")
                 ByteArrayInputStream.
                 BufferedInputStream.)
          out (BufferedOutputStream. (ByteArrayOutputStream.))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"^nREPL message must be a map."
                            (sut/recv (sut/bencode in out)))))))
