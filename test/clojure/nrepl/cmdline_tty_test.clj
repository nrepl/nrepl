(ns nrepl.cmdline-tty-test
  "TTY is not a full-featured transport (e.g. doesn't support ack), so are tested
   separately here."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [nrepl.cmdline :as cmd]
            [nrepl.server :as server]
            [nrepl.test-helpers :refer [free-port is+ sh]]
            [nrepl.transport :as transport])
  (:import (java.io BufferedReader PrintStream)
           (org.apache.commons.net.telnet TelnetClient)))

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
  (let [port (free-port)
        server-process (sh ["java" "-Dnreplacktest=y"
                            "-cp" (System/getProperty "java.class.path")
                            "nrepl.main"
                            "--port" (str port)
                            "--transport" "nrepl.transport/tty"])]
    (try
      (let [c (doto (TelnetClient.)
                (attempt-connection "localhost" port 100000))]
        (with-open [out (PrintStream. (.getOutputStream c))
                    br  (io/reader (.getInputStream c))]
          (doseq [l ["(System/getProperty \"nreplacktest\")"
                     "#?(:clj :clj-form)"
                     "#?(:cljs :cljs-form)"
                     "(clojure.core/require '[clojure.java.io :as io])"
                     "::io/xyz"
                     "(clojure.core/require '[clojure.set :as sets])"
                     "{::io/x 1 ::sets/x 2}"]]
            (.println out l))
          (.flush out)
          (is+ [#"^;; nREPL"
                #"^;; Clojure"
                "user=> \"y\""
                "user=> :clj-form"
                "user=> nil"
                "user=> :clojure.java.io/xyz"
                "user=> nil"
                "user=> {:clojure.java.io/x 1, :clojure.set/x 2}"]
               (vec (repeatedly 8 #(.readLine ^BufferedReader br)))))
        (.disconnect c))
      (finally
        (.destroy ^Process server-process)))))

(deftest no-tty-client
  (testing "Trying to connect with the tty transport should fail."
    (with-open [server (server/start-server :transport-fn #'transport/tty)]
      (let [options (cmd/connection-opts {:port      (:port server)
                                          :host      "localhost"
                                          :transport 'nrepl.transport/tty})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (cmd/interactive-repl server options)))))))
