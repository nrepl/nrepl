(ns nrepl.pprinting-test
  {:author "Chas Emerick"}
  (:use [nrepl.core-test :only (def-repl-test repl-server-fixture)]
        clojure.test)
  (:require [nrepl.core :as repl]))

(use-fixtures :once repl-server-fixture)

(comment ; TODO
  (defmacro def-pp-test
    [name & body]
    (when (repl/pretty-print-available?)
      `(def-repl-test ~name
         (~'repl-receive "(set! nrepl/*pretty-print* true)")
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
    (is (repl-value "nrepl/*pretty-print*"))
    (is (repl-value "(nrepl/pretty-print?)"))
    (repl-receive "(set! nrepl/*pretty-print* false)")
    (is (not (repl-value "nrepl/*pretty-print*")))
    (is (not (repl-value "(nrepl/pretty-print?)")))))
