(ns cemerick.nrepl.helpers-test
  (:use [cemerick.nrepl-test :only (def-repl-test repl-server-fixture)]
    clojure.test)
  (:require
    [cemerick.nrepl.helpers :as helpers]))

(use-fixtures :once repl-server-fixture)

(deftest escape-and-string-argument
  (are [string escaped] (= escaped (helpers/escape string))
    "a" "a"
    "\"a" "\\\"a")
  (are [string arg] (= arg (helpers/string-argument string))
    "a" "\"a\""
    "\"a" "\"\\\"a\""))

(def-repl-test load-code-with-debug-info
  (repl "\n\n\n(defn function [])")
  (is (= {:file "NO_SOURCE_PATH" :line 4}
        (repl-value "(-> #'function meta (select-keys [:file :line]))")))
  (repl (apply format
          "(cemerick.nrepl.helpers/load-with-debug-info %s %s %s)"
          (map helpers/string-argument
            ["\n\n\n\n\n\n\n\n\n(defn dfunction [])" "path/from/source/root.clj" "root.clj"])))
  (is (= {:file "path/from/source/root.clj" :line 10}
        (repl-value "(-> #'dfunction meta (select-keys [:file :line]))"))))