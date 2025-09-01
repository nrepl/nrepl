(ns nrepl.util.completion-test
  "Unit tests for completion utilities."
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as mc]
            [nrepl.util.completion :as completion :refer [completions]]
            [nrepl.test-helpers :refer [is+]]))

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
    (is+ ["alength" "alias" "all-ns" "alter" "alter-meta!" "alter-var-root" "aset-long"]
         (candidates "al" 'clojure.core))

    (is+ ["jio/make-input-stream" "jio/make-output-stream" "jio/make-parents" "jio/make-reader" "jio/make-writer"]
         (candidates "jio/make" 'clojure.core))

    (is+ ["clojure.core/alter" "clojure.core/alter-meta!" "clojure.core/alter-var-root"]
         (candidates "clojure.core/alt" 'clojure.core))

    (is+ [] (candidates "fake-ns-here/"))

    (is+ [] (candidates "/")))

  (testing "namespace completion"
    (is+ ["nrepl.util.completion" "nrepl.util.completion-test"]
         (candidates "nrepl.util.comp"))

    (is+ (mc/embeds ["clojure.core" "clojure.core.ArrayChunk" "clojure.core.ArrayManager" "clojure.core.IVecImpl" "clojure.core.Vec" "clojure.core.VecNode" "clojure.core.VecSeq" "clojure.core.protocols" "clojure.core.protocols.InternalReduce"])
         (candidates "clojure.co")))

  (testing "namespace completion with java classes"
    (is+ (mc/embeds ["nrepl.test.Dummy"])
         (candidates "nrepl.t")))

  (testing "Java instance methods completion"
    (is+ [".toUpperCase"] (candidates ".toUpper"))
    (is (distinct-candidates? ".toString")))

  (testing "static members completion"
    (is+ ["System/out"] (candidates "System/o"))
    (is+ ["java.lang.System/out"] (candidates "java.lang.System/out"))

    (is+ (mc/embeds ["String/valueOf"])  (candidates "String/"))
    (is (distinct-candidates? "String/v"))

    (is+ (mc/mismatch (mc/embeds ["String/indexOf" ".indexOf"])) (candidates "String/")))

  (testing "candidate types"
    (is+ (mc/embeds [{:candidate "t-var"
                      :ns "nrepl.util.completion-test"
                      :type :var}])
         (completions "t-var" 'nrepl.util.completion-test))
    (is+ (mc/embeds [{:candidate "t-var"
                      :type :var
                      :ns "nrepl.util.completion-test"
                      :doc "var"}])
         (completions "t-var" 'nrepl.util.completion-test {:extra-metadata #{:arglists :doc}}))
    (is+ (mc/embeds [{:candidate "t-fn"
                      :ns "nrepl.util.completion-test"
                      :type :function}])
         (completions "t-fn" 'nrepl.util.completion-test))
    (is+ (mc/embeds [{:candidate "t-fn"
                      :type :function
                      :ns "nrepl.util.completion-test"
                      :arglists '("[x]")
                      :doc "fn"}])
         (completions "t-fn" 'nrepl.util.completion-test {:extra-metadata #{:arglists :doc}}))
    (is+ (mc/embeds [{:candidate "t-macro"
                      :ns "nrepl.util.completion-test"
                      :type :macro}])
         (completions "t-macro" 'nrepl.util.completion-test))
    (is+ (mc/embeds [{:candidate "t-macro"
                      :type :macro
                      :ns "nrepl.util.completion-test"
                      :arglists '("[y]")
                      :doc "macro"}])
         (completions "t-macro" 'nrepl.util.completion-test {:extra-metadata #{:arglists :doc}}))
    (is+ (mc/embeds [{:candidate "unquote" :type :var, :ns "clojure.core"}])
         (completions "unquote" 'clojure.core))
    (is+ (mc/embeds [{:candidate "if" :type :special-form}])
         (completions "if" 'clojure.core))
    (is+ (mc/embeds [{:candidate "UnsatisfiedLinkError" :type :class}])
         (completions "Unsatisfied" 'clojure.core))
    ;; ns with :doc meta
    (is+ (mc/embeds [{:candidate "clojure.core"
                      :file "clojure/core.clj"
                      :type :namespace}])
         (completions "clojure.core" 'clojure.core))
    (is+ (mc/embeds [{:candidate "clojure.core"
                      :type :namespace
                      :file "clojure/core.clj"}])
         (completions "clojure.core" 'clojure.core))
    ;; ns with docstring argument
    (is+ (mc/embeds [{:candidate "nrepl.util.completion-test" :type :namespace}])
         (completions "nrepl.util.completion-test" 'clojure.core))
    (is+ (mc/embeds [{:candidate "Integer/parseInt" :type :static-method}])
         (completions "Integer/parseInt" 'clojure.core))
    (is+ (mc/embeds [{:candidate "File/separator", :type :static-field}])
         (completions "File/" 'nrepl.util.completion))
    (is+ (mc/embeds [{:candidate ".toString" :type :method}])
         (completions ".toString" 'clojure.core))))

(deftest keyword-completions-test
  (testing "colon prefix"
    (is+ (mc/embeds [":doc" ":refer" ":refer-clojure"])
         (candidates ":" *ns*)))

  (testing "unqualified keywords"
    (do #{:t-key-foo :t-key-bar :t-key-baz :t-key/quux}
        (is+ (mc/embeds [":t-key-foo" ":t-key-bar" ":t-key-baz" ":t-key/quux"])
             (candidates ":t-key" *ns*))))

  (testing "auto-resolved unqualified keywords"
    (do #{::foo ::bar ::baz}
        (is+ (mc/embeds [":nrepl.util.completion-test/bar" ":nrepl.util.completion-test/baz"])
             (candidates ":nrepl.util.completion-test/ba" *ns*))
        (is+ (mc/embeds ["::bar" "::baz"])
             (candidates "::ba" 'nrepl.util.completion-test))))

  (testing "auto-resolved qualified keywords"
    (do #{:nrepl.core/aliased-one :nrepl.core/aliased-two}
        (require '[nrepl.core :as core])
        (is+ (mc/embeds ["::core/aliased-one" "::core/aliased-two"])
             (candidates "::core/ali" *ns*))))

  (testing "namespace aliases"
    (is+ (mc/embeds ["::set"])
         (candidates "::s" 'nrepl.util.completion-test)))

  (testing "namespace aliases without namespace"
    (is+ [] (candidates "::/" *ns*))))
