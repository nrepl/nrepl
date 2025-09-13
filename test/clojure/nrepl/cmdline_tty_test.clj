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
    (future (with-open [out (.getInputStream proc)
                        err (.getErrorStream proc)
                        in (.getOutputStream proc)]
              (let [_pump-out (doto (Pipe. out System/out) .start)
                    _pump-err (doto (Pipe. err System/err) .start)
                    _pump-in (ClosingPipe. System/in in)]
                (.waitFor proc))))
    proc))

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
                   (.println "#?(:clj :clj-form)")
                   (.println "#?(:cljs :cljs-form)")
                   (.println "(clojure.core/require '[clojure.java.io :as io])")
                   (.println "::io/xyz")
                   (.println "(clojure.core/require '[clojure.set :as sets])")
                   (.println "{::io/x 1 ::sets/x 2}")
                   (.flush))
            resp (doall (repeatedly 9 #(.readLine br)))
            _    (.disconnect c)
            expected (if transport/clojure<1-10
                       ;; Continued error behavior in Clojure <1.10
                       ["user=> \"y\""
                        "user=> :clj-form"
                        "user=> "
                        nil nil nil nil]
                       ["user=> \"y\""
                        "user=> :clj-form"
                        "user=> "
                        "user=> nil"
                        "user=> :clojure.java.io/xyz"
                        "user=> nil"
                        "user=> {:clojure.java.io/x 1, :clojure.set/x 2}"])]
        (is (= expected
               (drop 2 resp))))
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
