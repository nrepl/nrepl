(ns cemerick.nrepl.tooling.load-test
  (:use [cemerick.nrepl-test :only (def-repl-test repl-server-fixture)]
    clojure.test)
  (:require
    [cemerick.nrepl.helpers :as helpers]))

(use-fixtures :once repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (repl "\n\n\n(defn function [])")
  (is (= {:file "NO_SOURCE_PATH" :line 4}
        (repl-value "(-> #'function meta (select-keys [:file :line]))")))
  (repl "(require 'cemerick.nrepl.tooling.load)")
  (repl (apply format
          "(cemerick.nrepl.tooling.load/load-with-debug-info %s %s %s)"
          (map helpers/string-argument
            ["\n\n\n\n\n\n\n\n\n(defn dfunction [])" "path/from/source/root.clj" "root.clj"])))
  (is (= {:file "path/from/source/root.clj" :line 10}
        (repl-value "(-> #'dfunction meta (select-keys [:file :line]))"))))