(ns nrepl.util.completion-test
  "Unit tests for completion utilities."
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [nrepl.util.completion :as completion :refer [completions]]))

(def t-var "var" nil)
(defn t-fn "fn" [x] x)
(defmacro t-macro "macro" [y] y)

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

  (testing "namespace completion"
    (is (= '("nrepl.util.completion" "nrepl.util.completion-test")
           (candidates "nrepl.util.comp")))

    (is (set/subset?
         #{"clojure.core" "clojure.core.ArrayChunk" "clojure.core.ArrayManager" "clojure.core.IVecImpl" "clojure.core.Vec" "clojure.core.VecNode" "clojure.core.VecSeq" "clojure.core.protocols" "clojure.core.protocols.InternalReduce"}
         (set (candidates "clojure.co")))))

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
    (is (some #{{:candidate "t-var"
                 :type :var}}
              (completions "t-var" 'nrepl.util.completion-test)))
    (is (some #{{:candidate "t-var"
                 :type :var
                 :doc "var"}}
              (completions "t-var" 'nrepl.util.completion-test {:extra-metadata #{:arglists :doc}})))
    (is (some #{{:candidate "t-fn"
                 :type :function}}
              (completions "t-fn" 'nrepl.util.completion-test)))
    (is (some #{{:candidate "t-fn"
                 :type :function
                 :arglists "([x])"
                 :doc "fn"}}
              (completions "t-fn" 'nrepl.util.completion-test {:extra-metadata #{:arglists :doc}})))
    (is (some #{{:candidate "t-macro"
                 :type :macro}}
              (completions "t-macro" 'nrepl.util.completion-test)))
    (is (some #{{:candidate "t-macro"
                 :type :macro
                 :arglists "([y])"
                 :doc "macro"}}
              (completions "t-macro" 'nrepl.util.completion-test {:extra-metadata #{:arglists :doc}})))
    (is (some #{{:candidate "unquote" :type :var}}
              (completions "unquote" 'clojure.core)))
    (is (some #{{:candidate "if" :ns "clojure.core" :type :special-form}}
              (completions "if" 'clojure.core)))
    (is (some #{{:candidate "UnsatisfiedLinkError" :type :class}}
              (completions "Unsatisfied" 'clojure.core)))
    ;; ns with :doc meta
    (is (some #{{:candidate "clojure.core"
                 :type :namespace}}
              (completions "clojure.core" 'clojure.core)))
    (is (some #{{:candidate "clojure.core"
                 :type :namespace
                 :doc "Fundamental library of the Clojure language"}}
              (completions "clojure.core" 'clojure.core {:extra-metadata #{:doc}})))
    ;; ns with docstring argument
    (is (some #{{:candidate "nrepl.util.completion-test"
                 :type :namespace}}
              (completions "nrepl.util.completion-test" 'clojure.core)))
    (is (some #{{:candidate "nrepl.util.completion-test"
                 :type :namespace
                 :doc "Unit tests for completion utilities."}}
              (completions "nrepl.util.completion-test" 'clojure.core {:extra-metadata #{:doc}})))
    (is (some #{{:candidate "Integer/parseInt" :type :static-method}}
              (completions "Integer/parseInt" 'clojure.core)))
    (is (some #{{:candidate "File/separator", :type :static-method}}
              (completions "File/" 'nrepl.util.completion)))
    (is (some #{{:candidate ".toString" :type :method}}
              (completions ".toString" 'clojure.core)))))

(deftest keyword-completions-test
  (testing "colon prefix"
    (is (set/subset? #{":doc" ":refer" ":refer-clojure"}
                     (set (candidates ":" *ns*)))))

  (testing "unqualified keywords"
    (do #{:t-key-foo :t-key-bar :t-key-baz :t-key/quux}
        (is (set/subset? #{":t-key-foo" ":t-key-bar" ":t-key-baz" ":t-key/quux"}
                         (set (candidates ":t-key" *ns*))))))

  (testing "auto-resolved unqualified keywords"
    (do #{::foo ::bar ::baz}
        (is (set/subset? #{":nrepl.util.completion-test/bar" ":nrepl.util.completion-test/baz"}
                         (set (candidates ":nrepl.util.completion-test/ba" *ns*))))
        (is (set/subset? #{"::bar" "::baz"}
                         (set (candidates "::ba" 'nrepl.util.completion-test))))))

  (testing "auto-resolved qualified keywords"
    (do #{:nrepl.core/aliased-one :nrepl.core/aliased-two}
        (require '[nrepl.core :as core])
        (is (set/subset? #{"::core/aliased-one" "::core/aliased-two"}
                         (set (candidates "::core/ali" *ns*))))))

  (testing "namespace aliases"
    (is (set/subset? #{"::set"}
                     (set (candidates "::s" 'nrepl.util.completion-test)))))

  (testing "namespace aliases without namespace"
    (is (empty? (candidates "::/" *ns*)))))
