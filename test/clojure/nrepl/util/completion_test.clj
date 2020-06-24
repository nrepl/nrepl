(ns nrepl.util.completion-test
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [nrepl.util.completion :as completion :refer [completions]]))

(defn- candidates
  "Return only the candidate names without any additional
  metadata for them."
  ([prefix]
   (candidates prefix *ns*))
  ([prefix ns]
   (map :candidate (completions prefix ns))))

(defn- distinct-candidates?
  "Return true if every candidate occurs in the list of
   candidates only once."
  ([prefix]
   (distinct-candidates? prefix *ns*))
  ([prefix ns]
   (apply distinct? (candidates prefix ns))))

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
    (is (= '("nrepl.util.completion" "nrepl.util.completion-test")
           (candidates "nrepl.util.comp"))))

  (testing "Java instance methods completion"
    (is (= '(".toUpperCase")
           (candidates ".toUpper")))

    (is (distinct-candidates? ".toString")))

  (testing "static members completion"
    (is (= '("System/out")
           (candidates "System/o")))

    (is (= '("java.lang.System/out")
           (candidates "java.lang.System/out")))

    (is (some #{"String/valueOf"} (candidates "String/")))
    (is (distinct-candidates? "String/v"))

    (is (not (some #{"String/indexOf" ".indexOf"} (candidates "String/")))))

  (testing "candidate types"
    (is (some #{{:candidate "comment" :type :macro}}
              (completions "comment" 'clojure.core)))
    (is (some #{{:candidate "commute" :type :function}}
              (completions "commute" 'clojure.core)))
    (is (some #{{:candidate "unquote" :type :var}}
              (completions "unquote" 'clojure.core)))
    (is (some #{{:candidate "if" :ns "clojure.core" :type :special-form}}
              (completions "if" 'clojure.core)))
    (is (some #{{:candidate "UnsatisfiedLinkError" :type :class}}
              (completions "Unsatisfied" 'clojure.core)))
    (is (some #{{:candidate "clojure.core" :type :namespace}}
              (completions "clojure.core" 'clojure.core)))
    (is (some #{{:candidate "Integer/parseInt" :type :static-method}}
              (completions "Integer/parseInt" 'clojure.core)))
    (is (some #{{:candidate ".toString" :type :method}}
              (completions ".toString" 'clojure.core)))))
