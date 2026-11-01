(ns nrepl.misc-test
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.misc :as misc]
            [nrepl.test-helpers :as th]
            [clojure.java.io :as io])
  (:import [java.net URI]))

(deftest sanitize-meta-test
  (is (not-empty (:file (misc/sanitize-meta {:file "clojure/core.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/sanitize-meta {:file "/foo/bar/baz.clj"}))))

  (is (= (th/dir-sep->sys "/foo/bar/baz.clj")
         (:file (misc/sanitize-meta {:file (io/file "/foo/bar/baz.clj")}))))

  (is (= "https://foo.bar"
         (:file (misc/sanitize-meta {:file (.toURL (URI. "https://foo.bar"))}))))

  (testing "no :file for metadata without one, as Bencode has no nil"
    (is (not (contains? (misc/sanitize-meta {:name 'x}) :file)))))
