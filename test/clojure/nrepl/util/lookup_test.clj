(ns nrepl.util.lookup-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [nrepl.util.lookup :as l :refer [lookup]]))

(deftest lookup-test
  (testing "special sym lookup"
    (is (not-empty (lookup 'clojure.core 'if))))

  (testing "fully qualified sym lookup"
    (is (not-empty (lookup 'clojure.core 'clojure.core/map))))

  (testing "aliased sym lookup"
    (is (not-empty (lookup 'nrepl.util.lookup 'str/upper-case))))

  (testing "non-qualified lookup"
    (is (not-empty (lookup 'clojure.core 'map))))

  (testing "Java sym lookup"
    (is (empty? (lookup 'clojure.core 'String)))))
