(ns clojure.tools.nrepl.load-file-test
  {:author "Chas Emerick"}
  (:import (java.io File))
  (:use [clojure.tools.nrepl-test :only (def-repl-test repl-server-fixture
                                          project-base-dir)]
        clojure.test)
  (:require [clojure.tools.nrepl :as nrepl]))

(use-fixtures :each repl-server-fixture)

(defmacro eastwood-ignore-unused-ret
  "Use this macro to mark evaluations that intentionally throw away
  their return values so we can tell Eastwood to ignore them.

  Taken from piggieback project, in order to keep things consisent
  instead of using # in front of all the doalls."
  [body]
  body)

(def-repl-test load-code-with-debug-info
  (eastwood-ignore-unused-ret
   (doall (nrepl/message timeout-session
                         {:op "load-file" :file "\n\n\n(defn function [])"})))
  (is (contains?
       ;; different versions of Clojure use different default :file metadata
       #{[{:file "NO_SOURCE_PATH" :line 4}]
         [{:file "NO_SOURCE_FILE" :line 4}]}
       (repl-values timeout-session
                    (nrepl/code
                     (-> #'function
                         meta
                         (select-keys [:file :line]))))))
  (eastwood-ignore-unused-ret
   (doall (nrepl/message timeout-session {:op "load-file"
                                          :file "\n\n\n\n\n\n\n\n\n(defn afunction [])"
                                          :file-path "path/from/source/root.clj"
                                          :file-name "root.clj"})))
  (is (= [{:file "path/from/source/root.clj" :line 10}]
         (repl-values timeout-session
                      (nrepl/code
                       (-> #'afunction
                           meta
                           (select-keys [:file :line])))))))

(def-repl-test load-file-with-debug-info
  (eastwood-ignore-unused-ret
   (doall
    (nrepl/message timeout-session
                   {:op "load-file"
                    :file (slurp (File. project-base-dir "load-file-test/clojure/tools/nrepl/load_file_sample.clj"))
                    :file-path "clojure/tools/nrepl/load_file_sample.clj"
                    :file-name "load_file_sample.clj"})))
  (is (= [{:file "clojure/tools/nrepl/load_file_sample.clj" :line 5}]
         (repl-values timeout-session
                      (nrepl/code
                       (-> #'clojure.tools.nrepl.load-file-sample/dfunction
                           meta
                           (select-keys [:file :line])))))))

(def-repl-test load-file-with-print-vars
  (set! *print-length* 3)
  (set! *print-level* 3)
  (eastwood-ignore-unused-ret
   (doall
    (nrepl/message session {:op "load-file"
                            :file "(def a (+ 1 (+ 2 (+ 3 (+ 4 (+ 5 6))))))
                                   (def b 2) (def c 3) (def ^{:internal true} d 4)"
                            :file-path "path/from/source/root.clj"
                            :file-name "root.clj"})))
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
