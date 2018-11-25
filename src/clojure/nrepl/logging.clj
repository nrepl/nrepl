(ns nrepl.logging)

(defn info
  [& args]
  (apply println "INFO: " args))
