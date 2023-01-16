(ns nrepl.cmdline-tty-test
  "TTY is not a full-featured transport (e.g. doesn't support ack), so are tested
   separately here."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.cmdline :as cmd]
            [nrepl.server :as server]
            [nrepl.transport :as transport])
  (:import [com.hypirion.io ClosingPipe Pipe]
           [org.apache.commons.net.telnet TelnetClient]
           nrepl.server.Server))

(defn- sh
  "A version of clojure.java.shell/sh that streams in/out/err.
  Taken and edited from https://github.com/technomancy/leiningen/blob/f7e1adad6ff5137d6ea56bc429c3b620c6f84128/leiningen-core/src/leiningen/core/eval.clj"
  ^Process
  [& cmd]
  (let [proc (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String cmd))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (.destroy proc))))
    (with-open [out (.getInputStream proc)
                err (.getErrorStream proc)
                in (.getOutputStream proc)]
      (let [pump-out (doto (Pipe. out System/out) .start)
            pump-err (doto (Pipe. err System/err) .start)
            pump-in (ClosingPipe. System/in in)]
        proc))))

(defn- attempt-connection [client host port timeout-ms]
  (loop [n (/ timeout-ms 500)]
    (or (try
          (.connect ^TelnetClient client ^String host ^int port)
          client
          (catch java.net.ConnectException ex
            (when-not (pos? n)
              (throw ex))))
        (do
          (Thread/sleep 500)
          (recur (dec n))))))

(deftest ^:slow tty-server
  (let [^int free-port (with-open [ss (java.net.ServerSocket.)]
                         (.bind ss nil)
                         (.getLocalPort ss))
        ^Process
        server-process (apply sh ["java" "-Dnreplacktest=y"
                                  "-cp" (System/getProperty "java.class.path")
                                  "nrepl.main"
                                  "--port" (str free-port)
                                  "--transport" "nrepl.transport/tty"])]
    (try
      (let [c    (doto (TelnetClient.)
                   (attempt-connection "localhost" free-port 100000))
            out  (java.io.PrintStream. (.getOutputStream c))
            br   (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream c)))
            _    (doto out
                   (.println "(System/getProperty \"nreplacktest\")")
                   (.flush))
            resp (doall (repeatedly 3 #(.readLine br)))
            _    (.disconnect c)]
        (is (= "user=> \"y\""
               (last resp))))
      (finally
        (.destroy server-process)))))

(deftest no-tty-client
  (testing "Trying to connect with the tty transport should fail."
    (with-open [^Server server (server/start-server :transport-fn #'transport/tty)]
      (let [options (cmd/connection-opts {:port      (:port server)
                                          :host      "localhost"
                                          :transport 'nrepl.transport/tty})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (cmd/interactive-repl server options)))))))
