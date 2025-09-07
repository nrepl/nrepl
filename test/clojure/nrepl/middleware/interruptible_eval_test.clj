(ns nrepl.middleware.interruptible-eval-test
  {:author "Oleksandr Yakushev"}
  (:require
   [clojure.test :refer :all]
   [clojure.string :as str]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture]]))

(use-fixtures :each repl-server-fixture)

(defn- find-frame-ending-with [^Throwable ex, suffix]
  (some #(when (str/ends-with? (.getClassName ^StackTraceElement %) suffix) %)
        (.getStackTrace ex)))

(def-repl-test preserves-source-location-test
  (testing "plain eval remembers source file for the compiled function"
    (dorun (repl-eval session "(in-ns 'nrepl.middleware.interruptible-eval-test)"))
    (dorun (nrepl/message session
                          {:op "eval" :code "(defn div0 [] (/ 1 0))"
                           :file "src/acme/foo.clj" :line 42}))
    (dorun (repl-eval session "(div0)"))
    (let [[resp] (repl-values session "(.getFileName (find-frame-ending-with *e \"div0\"))")]
      (is (= "foo.clj" resp)))))
