(ns nrepl.util.lookup-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [nrepl.util.lookup :as l :refer [lookup]]))

(deftest lookup-test
  (testing "special sym lookup"
    (is (not-empty (lookup 'clojure.core 'if))))

  (testing "fully qualified sym lookup"
    (is (not-empty (lookup 'nrepl.util.lookup 'clojure.core/map))))

  (testing "aliased sym lookup"
    (is (not-empty (lookup 'nrepl.util.lookup 'str/upper-case))))

  (testing "non-qualified lookup"
    (is (not-empty (lookup 'clojure.core 'map)))
    (is (not-empty (lookup 'nrepl.util.lookup 'map))))

  (testing "macro lookup"
    (is (= {:ns "clojure.core"
            :name "future"
            :macro "true"}
           (select-keys (lookup 'clojure.core 'future) [:ns :name :macro]))))

  (testing "special form lookup"
    (is (= {:ns "clojure.core"
            :name "let"
            :special-form "true"}
           (select-keys (lookup 'clojure.core 'let) [:ns :name :special-form]))))

  (testing "Java sym lookup"
    (is (empty? (lookup 'clojure.core 'String)))))

(deftest normalize-meta-test
  (is (not-empty (:file (l/normalize-meta {:file "clojure/core.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (l/normalize-meta {:file "/foo/bar/baz.clj"})))))
