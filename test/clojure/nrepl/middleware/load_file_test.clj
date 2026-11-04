(ns nrepl.middleware.load-file-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.core :as nrepl]
   [nrepl.test-helpers :refer :all]))

(def-repl-test load-code-with-debug-info
  (message! {:op "load-file" :file "\n\n\n(defn function [])"})
  (is+ {:file "NO_SOURCE_PATH" :line 4}
       (repl-value1 (nrepl/code
                     (-> #'function
                         meta
                         (select-keys [:file :line])))))

  (message! {:op "load-file"
             :file "\n\n\n\n\n\n\n\n\n(defn afunction [])"
             :file-path "path/from/source/root.clj"
             :file-name "root.clj"})
  (is+ {:file "path/from/source/root.clj" :line 10}
       (repl-value1 (nrepl/code
                     (-> #'afunction
                         meta
                         (select-keys [:file :line]))))))

(def-repl-test load-file-with-debug-info
  (message! {:op "load-file"
             :file (slurp "load-file-test/nrepl/load_file_sample.clj")
             :file-path "nrepl/load_file_sample.clj"
             :file-name "load_file_sample.clj"})
  (is+ {:file "nrepl/load_file_sample.clj" :line 5}
       (repl-value1 (nrepl/code
                     (-> #'nrepl.load-file-sample/dfunction
                         meta
                         (select-keys [:file :line]))))))

(def-repl-test load-file-with-print-vars
  (message! {:op "load-file"
             :file "(def a (+ 1 (+ 2 (+ 3 (+ 4 (+ 5 6))))))
                                   (def b 2) (def c 3) (def ^{:internal true} d 4)"
             :file-path "path/from/source/root.clj"
             :file-name "root.clj"})
  (is (= 4 (repl-value1 (nrepl/code d)))))

(def-repl-test load-file-response-no-ns
  (is+ {:ns mc/absent}
       (message! {:op "load-file"
                  :file "(ns foo) (def x 5)"
                  :file-path "/path/to/source.clj"
                  :file-name "source.clj"})))

(def-repl-test load-file-huge-file
  (let [very-long-code (clojure.string/join "\n" (repeat 3000 "(+ 1 2 3 4 5 6 7 8 9 0)"))]
    (is (< 65536 (count very-long-code)))
    (is+ {:status #{:done}
          :value ["45"]}
         (message! {:op "load-file"
                    :file very-long-code
                    :file-path "/path/to/source.clj"
                    :file-name "source.clj"}))))

(def-repl-test load-file-empty-file
  (testing "empty file completes without error and produces no value"
    (is+ {:status #{:done}
          :ex mc/absent
          :value mc/absent}
         (message! {:op "load-file"
                    :file ""
                    :file-path "/path/to/empty.clj"
                    :file-name "empty.clj"}))))

(def-repl-test load-file-only-comments
  (testing "comments-only file completes without error and produces no value"
    (is+ {:status #{:done}
          :ex mc/absent
          :value mc/absent}
         (message! {:op "load-file"
                    :file ";; just a comment\n;; another comment\n"
                    :file-path "/path/to/comments.clj"
                    :file-name "comments.clj"}))))
