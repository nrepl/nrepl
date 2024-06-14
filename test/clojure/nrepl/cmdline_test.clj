(ns nrepl.cmdline-test
  {:author "Chas Emerick"}
  (:require
   [clojure.java.io :refer [as-file]]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [nrepl.ack :as ack]
   [nrepl.bencode :refer [write-bencode]]
   [nrepl.cmdline :as cmd]
   [nrepl.config :as config]
   [nrepl.core :as nrepl]
   [nrepl.core-test :refer [*server* *transport-fn* transport-fns]]
   [nrepl.server :as server]
   [nrepl.socket :refer [find-class unix-domain-flavor unix-socket-address]]
   [nrepl.test-helpers :as th]
   [nrepl.transport :as transport])
  (:import
   (com.hypirion.io Pipe ClosingPipe)
   (java.lang ProcessBuilder$Redirect)
   (java.net Socket SocketAddress)
   (java.nio.channels Channels SocketChannel)
   (java.nio.file Files)
   (nrepl.server Server)))

(defn create-tmpdir
  "Creates a temporary directory in parent (something clojure.java.io/as-path
  can handle) and returns its Path."
  [parent prefix]
  (let [nio-path (.toPath (as-file parent))]
    (Files/createTempDirectory nio-path prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn- start-server-for-transport-fn
  [transport-fn f]
  (with-open [^Server server (server/start-server
                              :transport-fn transport-fn
                              :handler (ack/handle-ack (server/default-handler)))]
    (binding [*server* server
              *transport-fn* transport-fn]
      (testing (str (-> transport-fn meta :name) " transport")
        (ack/reset-ack-port!)
        (f))
      (set! *print-length* nil)
      (set! *print-level* nil))))

(defmacro deftest-with-all-transports [name & body]
  `(deftest ~name
     (let [f# (fn [] ~@body)]
       (doseq [transport-fn# transport-fns]
         (start-server-for-transport-fn transport-fn# f#)))))

(defn- var->str
  [sym]
  (subs (str sym) 2))

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

(deftest repl-intro
  (is (re-find #"nREPL" (cmd/repl-intro))))

(deftest help
  (is (re-find #"Usage:" (cmd/help))))

(deftest args->options
  (#'config/reset-state)
  (is (= [{:middleware [#'clojure.core/identity], :repl "true"} ["extra" "args"]]
         (update (cmd/args->options ["-m" "clojure.core/identity" "-r" "true" "extra" "args"]) 0
                 select-keys [:middleware :repl])))

  (#'config/reset-state)
  (is (= {:port 5000
          :host "0.0.0.0"
          :repl-fn #'nrepl.cmdline/run-repl,
          :transport-fn #'nrepl.transport/bencode}
         (-> (cmd/args->options ["-p" "5000" "-h" "0.0.0.0"])
             first
             (select-keys [:port :host :repl-fn :transport-fn]))))

  (#'config/reset-state)
  (is (= {:bind "0.0.0.0"
          :port 5000
          :transport-fn #'transport/bencode
          :handler #'clojure.core/identity
          :repl-fn #'clojure.core/identity
          :ack 2000}
         (-> (cmd/args->options ["-b" "0.0.0.0" "-p" "5000" "--ack" "2000"
                                 "--handler" "clojure.core/identity"
                                 "--repl-fn" "clojure.core/identity"])
             first
             (select-keys [:bind :port :transport-fn :handler :ack :repl-fn]))))

  (#'config/reset-state))

(deftest ack-server
  (with-redefs [ack/send-ack (fn [_ _ _] true)]
    (let [output (with-out-str
                   (cmd/ack-server {:port 6000}
                                   {:ack-port 8000
                                    :transport #'transport/bencode
                                    :verbose true}))]
      (is (th/string= "ack'ing my port 6000 to other server running on port 8000\n"
                      output)))
    (let [output (with-out-str
                   (cmd/ack-server {:port 6000}
                                   {:ack-port 8000
                                    :transport #'transport/bencode}))]
      (is (= "" output)))))

(deftest server-started-message
  (with-open [^Server server (server/start-server
                              :transport-fn #'transport/bencode
                              :handler server/default-handler)]
    (is (re-find #"nREPL server started on port \d+ on host .* - .*//.*:\d+"
                 (cmd/server-started-message
                  server
                  {:transport #'transport/bencode})))))

(deftest-with-all-transports ^:slow ack
  (let [ack-port (:port *server*)
        ^Process
        server-process (apply sh ["java" "-Dnreplacktest=y"
                                  "-cp" (System/getProperty "java.class.path")
                                  "nrepl.main"
                                  "--ack" (str ack-port)
                                  "--transport" (var->str *transport-fn*)])
        acked-port (ack/wait-for-ack 100000)]
    (try
      (is acked-port "Timed out waiting for ack")
      (when acked-port
        (with-open [^nrepl.transport.FnTransport
                    transport-2 (nrepl/connect :port acked-port
                                               :transport-fn *transport-fn*)]
          (let [client (nrepl/client transport-2 1000)]
            ;; just a sanity check
            (is (= "y"
                   (-> (nrepl/message client {:op "eval"
                                              :code "(System/getProperty \"nreplacktest\")"})
                       first
                       nrepl/read-response-value
                       :value))))))
      (finally
        (.destroy server-process)))))

(deftest-with-all-transports ^:slow explicit-port-argument
  (let [ack-port (:port *server*)
        free-port (with-open [ss (java.net.ServerSocket.)]
                    (.bind ss nil)
                    (.getLocalPort ss))
        ^Process
        server-process (apply sh ["java" "-Dnreplacktest=y"
                                  "-cp" (System/getProperty "java.class.path")
                                  "nrepl.main"
                                  "--port" (str free-port)
                                  "--ack" (str ack-port)
                                  "--transport" (var->str *transport-fn*)])
        acked-port (ack/wait-for-ack 100000)]
    (try
      (is (= acked-port free-port))
      (finally
        (Thread/sleep 2000)
        (.destroy server-process)))))

;;; Unix domain socket tests

(defn send-jdk-socket-message [message path]
  (let [^SocketAddress addr (unix-socket-address path)
        sock (SocketChannel/open addr)]
    ;; Assume it's safe to use Channels input/output streams here since
    ;; we're never reading and writing at the same time.
    ;; (cf. https://bugs.openjdk.java.net/browse/JDK-4509080 - not fixed
    ;; as of at least JDK 16).
    (with-open [out (Channels/newOutputStream sock)]
      (write-bencode out message))))

(defn send-junixsocket-message [message path]
  (let [^Class sock-class (find-class 'org.newsclub.net.unix.AFUNIXSocket)
        new-instance (.getDeclaredMethod sock-class "newInstance" nil)
        addr (unix-socket-address path)]
    (with-open [^Socket sock (.invoke new-instance nil nil)]
      (.connect sock addr)
      (write-bencode (.getOutputStream sock) message))))

(defn socket-file-exists?
  "Check whether the unix SOCK-FILE exists."
  [^java.io.File sock-file]
  (case unix-domain-flavor
    :jdk (.exists sock-file)
    :junixsocket
    ;; The .exists operation for socket files does not work on MS-Windows, thus
    ;; we attempt a socket connection.
    (try
      (let [sock-path (str sock-file)
            ^Class sock-class (find-class 'org.newsclub.net.unix.AFUNIXSocket)
            new-instance (.getDeclaredMethod sock-class "newInstance" nil)
            addr (unix-socket-address sock-path)]
        (with-open [^Socket sock (.invoke new-instance nil nil)]
          (.connect sock addr)
          true))
      (catch Exception _e
        false))))

(when unix-domain-flavor
  (deftest ^:slow basic-fs-socket-behavior
    (let [tmpdir (create-tmpdir "target" "socket-test-")
          sock-path (str tmpdir "/socket")
          sock-file (as-file sock-path)]
      (try
        ;; Use a Process rather than sh so we can see server errors
        (let [cmd (into-array ["java"
                               "-cp" (System/getProperty "java.class.path")
                               "nrepl.main" "--socket" sock-path])
              _ (println "CMD" (seq cmd))
              server (.start (doto (ProcessBuilder. ^"[Ljava.lang.String;" cmd)
                               (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                               (.redirectError ProcessBuilder$Redirect/INHERIT)))]
          (try
            ;; we want to ensure the server is up before trying to connect to it
            ;; this is done by waiting till the socket file is created, and then
            ;; waiting an extra 1s. (extra wait seems to help with test reliability
            ;; in CI. Question: why are we not using ack to do this? To investigate
            (while (not (socket-file-exists? sock-file))
              (Thread/sleep 100))
            (Thread/sleep 1000)
            (case unix-domain-flavor
              :jdk
              (send-jdk-socket-message {:code "(System/exit 42)" :op :eval}
                                       sock-path)
              :junixsocket
              (send-junixsocket-message {:code "(System/exit 42)" :op :eval}
                                        sock-path))
            (is (= 42 (.waitFor server)))
            (finally
              (.destroy server))))
        (finally
          (.delete sock-file)
          (Files/delete tmpdir))))))

(deftest ^:slow cmdline-namespace-resolution
  (testing "Ensuring that namespace resolution works in the cmdline repl"
    (let [test-input (str/join \newline ["::a"
                                         "(ns a)" "::a"
                                         "(ns b)" "::a"
                                         "(ns user)" "::a"
                                         "(require '[a :as z])" "::z/a"])
          expected-output [(str (keyword (str *ns*) "a"))
                           "nil" ":a/a"
                           "nil" ":b/a"
                           "nil" ":user/a"
                           "nil" ":a/a"]
          >devnull (fn [& _] nil)]
      (binding [*in* (java.io.PushbackReader. (java.io.StringReader. test-input))]
        (with-redefs [cmd/clean-up-and-exit >devnull]
          (with-open [server (server/start-server)]
            (let [results (atom [])]
              (#'cmd/run-repl (:host server) (:port server) {:prompt >devnull :err >devnull :out >devnull :value #(swap! results conj %)})
              (is (= expected-output @results)))))))))

(when unix-domain-flavor
  (deftest ^:slow can-connect-to-unix-socket
    (testing "We can connect to unix domain socker from the cli."
      (let [test-input      (str/join \newline ["::a"
                                                "(ns a)" "::a"
                                                "(ns b)" "::a"
                                                "(ns user)" "::a"
                                                "(require '[a :as z])" "::z/a"])
            expected-output [(str (keyword (str *ns*) "a"))
                             "nil" ":a/a"
                             "nil" ":b/a"
                             "nil" ":user/a"
                             "nil" ":a/a"]
            >devnull        (fn [& _] nil)
            tmpdir          (create-tmpdir "target" "socket-test-")
            sock-path       (str tmpdir "/socket")]
        (binding [*in* (java.io.PushbackReader. (java.io.StringReader. test-input))]
          (with-redefs [cmd/clean-up-and-exit >devnull]
            (with-open [server (server/start-server :socket sock-path)]
              (let [results (atom [])]
                (#'cmd/run-repl {:server  server
                                 :options {:prompt >devnull :err >devnull :out >devnull :value #(swap! results conj %)}})
                (is (= expected-output @results))))))))))
