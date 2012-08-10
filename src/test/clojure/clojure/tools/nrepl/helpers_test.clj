(ns ^{:author "Chas Emerick"}
  clojure.tools.nrepl.helpers-test
  (:import (java.io File))
  (:use [clojure.tools.nrepl-test :only (def-repl-test repl-server-fixture)]
    clojure.test)
  (:require
    [clojure.tools.nrepl :as nrepl]
    [clojure.tools.nrepl.helpers :as helpers]))

(def project-base-dir (File. (System/getProperty "nrepl.basedir" ".")))

(use-fixtures :once repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (repl-eval client "\n\n\n(defn function [])")
  (is (= [{:file "NO_SOURCE_PATH" :line 4}]
         (repl-values client "(-> #'function meta (select-keys [:file :line]))")))
  
  (repl-eval client
             (helpers/load-file-command
               "\n\n\n\n\n\n\n\n\n(defn dfunction [])"
               "path/from/source/root.clj"
               "root.clj"))
  
  (is (= [{:file "path/from/source/root.clj" :line 10}]
        (repl-values client
          (nrepl/code
            (-> #'dfunction
              meta
              (select-keys [:file :line])))))))

(def-repl-test load-file-with-debug-info
  (repl-eval client
             (helpers/load-file-command
               (File. project-base-dir "load-file-test/clojure/tools/nrepl/load_file_sample.clj")
               (File. project-base-dir "load-file-test")))
  (is (= [{:file "clojure/tools/nrepl/load_file_sample.clj" :line 5}]
         (repl-values client
           (nrepl/code 
             (-> #'clojure.tools.nrepl.load-file-sample/dfunction
               meta
               (select-keys [:file :line])))))))