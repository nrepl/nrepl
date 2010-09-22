(ns cemerick.nrepl.main
  (:gen-class)
  (:require [cemerick.nrepl :as repl]))

(defn- run-repl
  [port]
  (let [connection (repl/connect "localhost" port)
        {:keys [major minor incremental qualifier]} *clojure-version*]
    (println "network-repl")
    (print (str "Clojure " major \. minor \. incremental))
    (if (seq qualifier)
      (println (str \- qualifier))
      (println))
    (loop [ns "user"]
      (print (str ns "=> "))
      (flush)
      ((:send connection) (pr-str (read)))
      (let [{:keys [out ns value]} ((:receive connection))]
        (when (seq out) (print out))
        (recur ns)))))

(defn -main
  [& args]
  (let [[ssocket _] (repl/start-server)]
    (when ((into #{} args) "--repl")
      (run-repl (.getLocalPort ssocket)))))