(ns clojure.tools.nrepl.misc-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.nrepl.misc :refer [log]]))


(defmacro with-err-str
  "Evaluates exprs in a context in which *err* is bound to a fresh
  StringWriter.  Returns the string created by any nested printing
  calls.

  Taken from clojure.core/with-out-str and adjusted for *err*."
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body
       (str s#))))


(deftest log-test
  (testing "That the log doesn't convert non Throwable to nil"
    (is (= "ERROR: foo bar\n" (with-err-str (log "foo" "bar"))))))
