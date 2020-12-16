(ns nrepl.misc-test
  (:require [clojure.test :refer [deftest is]]
            [nrepl.misc :as misc]))

(deftest normalize-meta-test
  (is (not-empty (:file (misc/normalize-meta {:file "clojure/core.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/normalize-meta {:file "/foo/bar/baz.clj"})))))
