(ns #^{:doc ""
       :author "Chas Emerick"}
  clojure.tools.nrepl.helpers-test
  (:import (java.io File))
  (:use [clojure.tools.nrepl-test :only (def-repl-test repl-server-fixture)]
    clojure.test)
  (:require
    [clojure.tools.nrepl :as nrepl]
    [clojure.tools.nrepl.helpers :as helpers]))

(deftest escape-and-string-argument
  (are [string escaped] (= escaped (helpers/escape string))
    "a" "a"
    "\"a" "\\\"a")
  (are [string arg] (= arg (helpers/string-argument string))
    "a" "\"a\""
    "\"a" "\"\\\"a\""))

(use-fixtures :once repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (repl-receive "\n\n\n(defn function [])")
  (is (= {:file "NO_SOURCE_PATH" :line 4}
        (repl-value "(-> #'function meta (select-keys [:file :line]))")))
  
  (repl-receive (helpers/load-file-command
                  "\n\n\n\n\n\n\n\n\n(defn dfunction [])"
                  "path/from/source/root.clj"
                  "root.clj"))
  
  (is (= [{:file "path/from/source/root.clj" :line 10}]
        (nrepl/values-with connection
          (-> #'dfunction
            meta
            (select-keys [:file :line]))))))

(def-repl-test load-file-with-debug-info
  (repl-receive (helpers/load-file-command
                  (File. "load-file-test/clojure/tools/nrepl/load_file_sample.clj")
                  (File. "load-file-test")))
  (is (= [{:file "clojure/tools/nrepl/load_file_sample.clj" :line 5}]
        (nrepl/values-with connection
          (-> #'clojure.tools.nrepl.load-file-sample/dfunction
            meta
            (select-keys [:file :line]))))))
