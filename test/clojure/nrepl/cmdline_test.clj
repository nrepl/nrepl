(ns nrepl.cmdline-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer :all]
   [nrepl.ack :as ack]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [*server* *transport-fn* transport-fns]]
   [nrepl.server :as server])
  (:import (com.hypirion.io Pipe ClosingPipe)))

(defn- start-server-for-transport-fn
  [transport-fn f]
  (with-open [server (server/start-server
                      :transport-fn transport-fn
                      :handler (ack/handle-ack (server/default-handler)))]
    (binding [*server* server
              *transport-fn* transport-fn]
      (testing (str (-> transport-fn meta :name) " transport\n")
        (ack/reset-ack-port!)
        (f))
      (set! *print-length* nil)
      (set! *print-level* nil))))

(defn- server-cmdline-fixture
  [f]
  (doseq [transport-fn transport-fns]
    (start-server-for-transport-fn transport-fn f)))

(use-fixtures :each server-cmdline-fixture)

(defn- var->str
  [sym]
  (subs (str sym) 2))

(defn- sh
  "A version of clojure.java.shell/sh that streams in/out/err.
  Taken and edited from https://github.com/technomancy/leiningen/blob/f7e1adad6ff5137d6ea56bc429c3b620c6f84128/leiningen-core/src/leiningen/core/eval.clj"
  [& cmd]
  (let [proc (.exec (Runtime/getRuntime) (into-array String cmd))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (.destroy proc))))
    (with-open [out (.getInputStream proc)
                err (.getErrorStream proc)
                in (.getOutputStream proc)]
      (let [pump-out (doto (Pipe. out System/out) .start)
            pump-err (doto (Pipe. err System/err) .start)
            pump-in (ClosingPipe. System/in in)]
        proc))))

(deftest ack
  (let [ack-port (:port *server*)
        server-process (apply sh ["java" "-Dnreplacktest=y"
                                  "-cp" (System/getProperty "java.class.path")
                                  "nrepl.main"
                                  "--ack" (str ack-port)
                                  "--transport" (var->str *transport-fn*)])
        acked-port (ack/wait-for-ack 10000)]
    (try
      (is acked-port "Timed out waiting for ack")
      (when acked-port
        (with-open [transport-2 (nrepl/connect :port acked-port
                                               :transport-fn *transport-fn*)]
          (let [client (nrepl/client transport-2 1000)]
            ;; just a sanity check
            (is (= "y"
                   (-> (nrepl/message client {:op :eval
                                              :code "(System/getProperty \"nreplacktest\")"})
                       first
                       nrepl/read-response-value
                       :value))))))
      (finally
        (.destroy server-process)))))

(deftest explicit-port-argument
  (let [ack-port (:port *server*)
        free-port (with-open [ss (java.net.ServerSocket.)]
                    (.bind ss nil)
                    (.getLocalPort ss))
        server-process (apply sh ["java" "-Dnreplacktest=y"
                                  "-cp" (System/getProperty "java.class.path")
                                  "nrepl.main"
                                  "--port" (str free-port)
                                  "--ack" (str ack-port)
                                  "--transport" (var->str *transport-fn*)])
        acked-port (ack/wait-for-ack 10000)]
    (try
      (is (= acked-port free-port))
      (finally
        (Thread/sleep 2000)
        (.destroy server-process)))))
