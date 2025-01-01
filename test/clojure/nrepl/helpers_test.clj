(ns nrepl.helpers-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture]]
   [nrepl.helpers :as helpers])
  (:import
   (java.io File)))

(def ^File project-base-dir (File. (System/getProperty "nrepl.basedir" ".")))

(use-fixtures :once repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (repl-values session
               (helpers/load-file-command
                "\n\n\n\n\n\n\n\n\n(defn dfunction [])"
                "path/from/source/root.clj"
                "root.clj"))

  (is (= [{:file "path/from/source/root.clj" :line 10}]
         (repl-values session
                      (nrepl/code
                       (-> #'dfunction
                           meta
                           (select-keys [:file :line])))))))

(def-repl-test load-file-with-debug-info
  (repl-values session
               (helpers/load-file-command
                (File. project-base-dir "load-file-test/nrepl/load_file_sample.clj")
                (File. project-base-dir "load-file-test")))
  (is (= [{:file (.replace "nrepl/load_file_sample.clj" "/" File/separator)
           :line 5}]
         (repl-values session
                      (nrepl/code
                       (-> #'nrepl.load-file-sample/dfunction
                           meta
                           (select-keys [:file :line])))))))
