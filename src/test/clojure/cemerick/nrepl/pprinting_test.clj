(ns cemerick.nrepl.pprinting-test
  (:use [cemerick.nrepl-test :only (def-repl-test repl-server-fixture)]
    clojure.test)
  (:require [cemerick.nrepl :as repl]))

(use-fixtures :once repl-server-fixture)

(defmacro def-pp-test
  [name & body]
  (when (repl/pretty-print-available?)
    `(def-repl-test ~name
       (~'repl-receive "(set! cemerick.nrepl/*pretty-print* true)")
       ~@body)))

(def-pp-test simple-collection
  (is (< 20 (->> (repl "(range 100)")
               repl/response-seq
               repl/combine-responses
               :value
               first 
               (filter #(= \newline %))
               count))))

(def-pp-test toggle-pprinting
  (is (repl-value "cemerick.nrepl/*pretty-print*"))
  (is (repl-value "(cemerick.nrepl/pretty-print?)"))
  (repl-receive "(set! cemerick.nrepl/*pretty-print* false)")
  (is (not (repl-value "cemerick.nrepl/*pretty-print*")))
  (is (not (repl-value "(cemerick.nrepl/pretty-print?)"))))