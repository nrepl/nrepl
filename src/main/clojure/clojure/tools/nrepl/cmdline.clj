(ns #^{:doc "A proof-of-concept command-line client for nREPL.  Please see
e.g. reply for a proper command-line nREPL client @
https://github.com/trptcolin/reply/"
       :author "Chas Emerick"}
      clojure.tools.nrepl.cmdline
  (:require [clojure.tools.nrepl :as repl]
            [clojure.tools.nrepl.transport :as transport])
  (:use (clojure.tools.nrepl [server :only (start-server)]
                             [ack :only (send-ack)])))

(defn- ensure-newline
  [s]
  (if (= "\n" (last s))
    s
    (str s \newline)))

(def colored-output
  {:err #(binding [*out* *err*]
           (print "\033[31m")
           (print %)
           (print "\033[m")
           (flush))
   :out print
   :value (fn [x]
            (print "\033[34m")
            (print x)
            (print "\033[m")
            (flush))})

(defn- run-repl
  ([port] (run-repl port nil))
  ([port {:keys [prompt err out value]
          :or {prompt #(print (str % "=> "))
               err println
               out println
               value println}}]
   (let [transport (repl/connect :host "localhost" :port port)
         client (repl/client-session (repl/client transport Long/MAX_VALUE))
         ns (atom "user")
         {:keys [major minor incremental qualifier]} *clojure-version*]
     (println "network-repl")
     (println (str "Clojure " (clojure-version)))
     (loop []
       (prompt @ns)
       (flush)
       (doseq [res (repl/message client {:op "eval" :code (pr-str (read))})]
         (when (:value res) (value (:value res)))
         (when (:out res) (out (:out res)))
         (when (:err res) (err (:err res)))
         (when (:ns res) (reset! ns (:ns res))))
       (recur)))))

(def #^{:private true} unary-options #{"--interactive" "--color"})

(defn- split-args
  [args]
  (loop [[arg & rem-args :as args] args
         options {}]
    (if-not (and arg (re-matches #"--.*" arg))
      [options args]
      (if (unary-options arg)
        (recur rem-args
               (assoc options arg true))
        (recur (rest rem-args)
               (assoc options arg (first rem-args)))))))

(defn -main
  [& args]
  (let [[options args] (split-args args)
        server (start-server :port (Integer/parseInt (or (options "--port") "0")))
        ^java.net.ServerSocket ssocket (:ss @server)]
    (when-let [ack-port (options "--ack")]
      (binding [*out* *err*]
        (println (format "ack'ing my port %d to other server running on port %s"
                         (.getLocalPort ssocket) ack-port)
                 (:status (send-ack (.getLocalPort ssocket) (Integer/parseInt ack-port))))))
    (if (options "--interactive")
      (run-repl (.getLocalPort ssocket) (when (options "--color") colored-output))
      ; need to hold process open with a non-daemon thread -- this should end up being super-temporary
      (Thread/sleep Long/MAX_VALUE))))
