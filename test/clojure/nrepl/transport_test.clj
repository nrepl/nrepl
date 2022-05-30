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

(deftest tty-read-conditional-test
  (testing "tty-read-msg is configured to read conditionals"
    (let [in (-> "(try nil (catch #?(:clj Throwable :cljr Exception) e nil))"
                 (java.io.StringReader.)
                 (java.io.PushbackReader.))
          out (ByteArrayOutputStream.)]
      (is (= ['(try nil (catch Throwable e nil))]
             (let [FnTransport (sut/tty in out nil)]
               (sut/recv FnTransport)     ;; :op "clone"
               (-> (sut/recv FnTransport) ;; :op "eval"
                   :code)))))))
