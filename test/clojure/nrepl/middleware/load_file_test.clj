(ns nrepl.middleware.load-file-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir]])
  (:import
   (java.io File)))

(use-fixtures :each repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (dorun (nrepl/message timeout-session
                        {:op "load-file" :file "\n\n\n(defn function [])"}))
  (is (contains?
       ;; different versions of Clojure use different default :file metadata
       #{[{:file "NO_SOURCE_PATH" :line 4}]
         [{:file "NO_SOURCE_FILE" :line 4}]}
       (repl-values timeout-session
                    (nrepl/code
                     (-> #'function
                         meta
                         (select-keys [:file :line]))))))
  (dorun (nrepl/message timeout-session {:op "load-file"
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
  (dorun
   (nrepl/message timeout-session
                  {:op "load-file"
                   :file (slurp (File. project-base-dir "load-file-test/nrepl/load_file_sample.clj"))
                   :file-path "nrepl/load_file_sample.clj"
                   :file-name "load_file_sample.clj"}))
  (is (= [{:file "nrepl/load_file_sample.clj" :line 5}]
         (repl-values timeout-session
                      (nrepl/code
                       (-> #'nrepl.load-file-sample/dfunction
                           meta
                           (select-keys [:file :line])))))))

(def-repl-test load-file-with-print-vars
  (set! *print-length* 3)
  (set! *print-level* 3)
  (dorun
   (nrepl/message session {:op "load-file"
                           :file "(def a (+ 1 (+ 2 (+ 3 (+ 4 (+ 5 6))))))
                                   (def b 2) (def c 3) (def ^{:internal true} d 4)"
                           :file-path "path/from/source/root.clj"
                           :file-name "root.clj"}))
  (is (= [4]
         (repl-values session (nrepl/code d)))))

(def-repl-test load-file-response-no-ns
  (is (not (contains? (nrepl/combine-responses
                       (nrepl/message session
                                      {:op "load-file"
                                       :file "(ns foo) (def x 5)"
                                       :file-path "/path/to/source.clj"
                                       :file-name "source.clj"}))
                      :ns))))

(def-repl-test load-file-huge-file
  (let [very-long-code (clojure.string/join "\n" (repeat 3000 "(+ 1 2 3 4 5 6 7 8 9 0)"))]
    (is (< 65536 (count very-long-code)))
    (is (= [45] (nrepl/response-values
                 (nrepl/message session
                                {:op "load-file"
                                 :file very-long-code
                                 :file-path "/path/to/source.clj"
                                 :file-name "source.clj"}))))))
