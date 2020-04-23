(ns nrepl.completion-test
  (:require [clojure.test :refer :all]
            [nrepl.completion :as completion :refer [completions]]))

(defn- candidates
  "Return only the candidate names without any additional
  metadata for them."
  ([prefix]
   (candidates prefix *ns*))
  ([prefix ns]
   (map :candidate (completions prefix ns))))

(deftest completions-test
  (testing "var completion"
    (is (= '("alength" "alias" "all-ns" "alter" "alter-meta!" "alter-var-root")
           (candidates "al" 'clojure.core)))

    (is (= '("jio/make-input-stream" "jio/make-output-stream" "jio/make-parents" "jio/make-reader" "jio/make-writer")
           (candidates "jio/make" 'clojure.core)))

    (is (= '("clojure.core/alter" "clojure.core/alter-meta!" "clojure.core/alter-var-root")
           (candidates "clojure.core/alt" 'clojure.core)))

    (is (= () (candidates "fake-ns-here/")))

    (is (= () (candidates "/"))))

  #_(is (= '("clojure.core" "clojure.core.ArrayChunk" "clojure.core.ArrayManager" "clojure.core.IVecImpl" "clojure.core.Vec" "clojure.core.VecNode" "clojure.core.VecSeq" "clojure.core.protocols" "clojure.core.protocols.InternalReduce")
           (candidates "clojure.co")))

  (testing "namespace completion"
    (is (= '("nrepl.completion" "nrepl.completion-test")
           (candidates "nrepl.completion"))))

  (testing "Java instance methods completion"
    (is (= '(".toUpperCase")
           (candidates ".toUpper"))))

  (testing "static members completion"
    (is (= '("System/out")
           (candidates "System/o")))

    (is (= '("java.lang.System/out")
           (candidates "java.lang.System/out")))

    (is (some #{"String/valueOf"} (candidates "String/")))

    (is (not (some #{"String/indexOf" ".indexOf"} (candidates "String/"))))))
