(ns nrepl.logging
  "Logging to be used at nREPL"
  {:added  "0.6.0"})

;; TODO: Use clojure.tools.logging if available
(def default-output *out*)

(defn info
  [& args]
  (binding [*out* default-output]
    (apply println "INFO: " args)))

(defn warn
  [& args]
  (binding [*out* default-output]
    (apply println "WARN: " args)))
