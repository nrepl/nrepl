(ns nrepl.logging
  "Logging to be used at nREPL"
  {:added  "0.6.0"})

;; TODO: Use clojure.tools.logging if available
(def stdout *out*)
(def stderr *err*)

(defn info
  [& args]
  (binding [*out* stdout]
    (apply println "INFO: " args)))

(defn warn
  [& args]
  (binding [*out* stderr]
    (apply println "WARN: " args)))

(defn error
  [& args]
  (binding [*out* stderr]
    (apply println "ERROR: " args)))
