(ns nrepl.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.matchers :as mc]
            [nrepl.test-helpers :refer [is+]]
            [nrepl.transport :as sut])
  (:import (java.io BufferedInputStream BufferedOutputStream ByteArrayInputStream ByteArrayOutputStream)))

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

(deftest parse-bencode-test
  (testing "keys are keywordized and byte strings are decoded, at any depth"
    (is (= {:op "eval"
            :code "(+ 1 2)"
            :id 42
            :nested {:list ["a" 1 {:deep "b"}]}
            :nrepl.middleware.print/print "clojure.core/pr"}
           (#'sut/parse-bencode-payload
            {"op" (.getBytes "eval")
             "code" (.getBytes "(+ 1 2)")
             "id" 42
             "nested" {"list" [(.getBytes "a") 1 {"deep" (.getBytes "b")}]}
             "nrepl.middleware.print/print" (.getBytes "clojure.core/pr")})))))

(deftest parse-bencode-question-mark-keys-test
  (testing "empty list under a ?-ending key is treated as nil"
    (is (= {:op "eval"
            :nrepl.middleware.print/stream? nil
            :nrepl.middleware.caught/print? 1
            :outer {:flag? nil}
            :non-empty-value? [1 2 3]
            :doesnt-end-with-q []}
           (#'sut/parse-bencode-payload
            {"op" "eval"
             "nrepl.middleware.print/stream?" []
             "nrepl.middleware.caught/print?" 1
             "outer" {"flag?" []}
             "non-empty-value?" [1 2 3]
             "doesnt-end-with-q" []})))))

(deftest parse-bencode-unencoded-test
  ;; Bytes that are not valid UTF-8, so any decoding would corrupt them.
  (let [binary-data (byte-array [-119 80 78 71 13 10 26 10 0 -1 -2 -128])]
    (is+ (mc/equals
          {:op "load-file"
           :file "(ns foo)"
           :data bytes?
           :-unencoded ["data"]})
         (#'sut/parse-bencode-payload {"op" (.getBytes "load-file")
                                       "file" (.getBytes "(ns foo)")
                                       "data" binary-data
                                       "-unencoded" [(.getBytes "data")]}))))
