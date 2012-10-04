(ns ^{:author "Chas Emerick"}
  clojure.tools.nrepl.load-file-test
  (:import (java.io File))
  (:use [clojure.tools.nrepl-test :only (def-repl-test repl-server-fixture)]
        clojure.test)
  (:require [clojure.tools.nrepl :as nrepl]))

(def project-base-dir (File. (System/getProperty "nrepl.basedir" ".")))

(use-fixtures :once repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (doall (nrepl/message timeout-session
                        {:op "load-file" :file "\n\n\n(defn function [])"}))
  (is (contains?
        ; different versions of Clojure use different default :file metadata
        #{[{:file "NO_SOURCE_PATH" :line 4}]
          [{:file "NO_SOURCE_FILE" :line 4}]}
        (repl-values timeout-session
          (nrepl/code
            (-> #'function
              meta
              (select-keys [:file :line]))))))
  
  (doall (nrepl/message timeout-session {:op "load-file"
                                         :file "\n\n\n\n\n\n\n\n\n(defn afunction [])"
                                         :file-path "path/from/source/root.clj"
                                         :file-name "root.clj"}))
  (is (= [{:file "path/from/source/root.clj" :line 10}]
         (repl-values timeout-session
           (nrepl/code
             (-> #'afunction
               meta
               (select-keys [:file :line])))))))

(def-repl-test load-file-with-debug-info
  (doall
    (nrepl/message timeout-session
                   {:op "load-file"
                    :file (slurp (File. project-base-dir "load-file-test/clojure/tools/nrepl/load_file_sample.clj"))
                    :file-path "clojure/tools/nrepl/load_file_sample.clj"
                    :file-name "load_file_sample.clj"}))
  (is (= [{:file "clojure/tools/nrepl/load_file_sample.clj" :line 5}]
         (repl-values timeout-session
           (nrepl/code 
             (-> #'clojure.tools.nrepl.load-file-sample/dfunction
               meta
               (select-keys [:file :line])))))))