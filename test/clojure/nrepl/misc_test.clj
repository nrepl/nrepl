(ns nrepl.misc-test
  (:require [clojure.test :refer [deftest is]]
            [nrepl.misc :as misc]
            [clojure.java.io :as io])
  (:import [java.net URL]))

(deftest sanitize-meta-test
  (is (not-empty (:file (misc/sanitize-meta {:file "clojure/core.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/sanitize-meta {:file "/foo/bar/baz.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/sanitize-meta {:file (io/file "/foo/bar/baz.clj")}))))

  (is (= "https://foo.bar"
         (:file (misc/sanitize-meta {:file (URL. "https://foo.bar")})))))
