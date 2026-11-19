(ns nrepl.core-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [nrepl.ack :as ack]
   [nrepl.core :as nrepl :refer [code]]
   [nrepl.server :as server]
   [nrepl.test-helpers :refer [*server* *transport* def-repl-test eval-value1
                               free-port get-state is+ session-id
                               with-nrepl-client with-nrepl-server]]
   [nrepl.transport :as transport]
   [nrepl.util.classloader])
  (:import (java.net SocketException)))

(def ^:private disconnected-re
  "Matches the messages a transport fails with once its peer is gone. Windows
  reports an aborted write as \"An established connection was aborted\"."
  #"(?i)(lost.*connection|socket closed|connection reset|broken pipe|connection was aborted)")

(deftest version-sanity-check
  (is (let [v (System/getenv "CLOJURE_VERSION")]
        (println "Running on Clojure" (clojure-version) ", expected:" v)
        (or (nil? v) (.startsWith ^String (clojure-version) v)))))

(def-repl-test ^:tcp-and-unix sanity
  (testing "eval"
    (are [expr result] (= result (repl-value1 (code expr)))
      (+ 1 2) 3
      *1 3
      (set! *print-length* 42) 42
      *print-length* 42))
  (testing "specified-namespace"
    (is+ {:ns "user", :value ["3"], :status #{:done}}
         (message! {:op "eval"
                    :ns "user"
                    :code (code (+ 1 2))}))
    (is+ {:ns "user", :value ["[\"user\" \"++\"]"], :status #{:done}}
         (message! {:op "eval"
                    :ns "user"
                    :code "(do
                                       (def ^{:dynamic true} ++ +)
                                       (mapv #(-> #'++ meta % str) [:ns :name]))"}))
    (is+ {:ns "user", :value ["5"], :status #{:done}}
         (message! {:op "eval"
                    :ns "user"
                    :code (code
                           (binding [++ -]
                             (++ 8 3)))})))
  (testing "multiple-expressions"
    (is+ [4 65536.0] (repl-values "(+ 1 3) (Math/pow 2 16)"))
    (is+ [4 20 1 0] (repl-values "(+ 2 2) (* *1 5) (/ *2 4) (- *3 4)"))
    (is+ [0] (repl-values "*1"))))

(def-repl-test unknown-op
  (is+ {:op "abc" :status #{:error :unknown-op :done}}
       (message! client {:op "abc"})))

(def-repl-test read-timeout
  ;; Clone through `client` so that a slow server can't fail the test before it
  ;; has even started.
  (let [short-session (nrepl/client-session (nrepl/client transport 200)
                                            :session (nrepl/new-session client))]
    (is (empty? (repl-values short-session "(Thread/sleep 1000) :ok")))))

(def-repl-test new-session-clones-by-id-or-map
  (repl-eval! session "(set! *print-length* 7)")
  (let [id (session-id session)]
    (testing "an unrelated new session starts from the defaults"
      (is (nil? (repl-value1 (nrepl/client-session client) "*print-length*"))))
    (testing "the session to clone given as an id"
      (is (= 7 (repl-value1 (nrepl/client-session client :clone id) "*print-length*"))))
    (testing "the session to clone given in a map"
      (is (= 7 (repl-value1 (nrepl/client-session client :clone {:session id
                                                                 :client-name "test"})
                            "*print-length*"))))))

(def-repl-test concurrent-message-handling
  (testing "multiple messages can be handled on the same connection concurrently"
    (let [sessions (vec (repeatedly 3 #(nrepl/client-session client)))
          start-time (System/currentTimeMillis)
          elapsed-times (mapv (fn [session eval-duration]
                                (let [expr (format "(Thread/sleep %s)" eval-duration)
                                      responses (repl-eval session expr)]
                                  (future
                                    (is (= [nil] (nrepl/response-values responses)))
                                    (- (System/currentTimeMillis) start-time))))
                              sessions
                              [600 300 0])]
      (is (apply > (map deref elapsed-times))))))

(def-repl-test ^:tcp-and-unix ensure-transport-closeable
  (is (= 5 (repl-value1 "5")))
  (is (instance? java.io.Closeable transport))
  (.close transport)
  (is (thrown? java.net.SocketException (repl-value1 "5"))))

(def-repl-test ^:tcp-and-unix ensure-server-closeable
  (.close ^java.io.Closeable *server*)
  (Thread/sleep 100)
  (is (thrown? java.net.SocketException (with-nrepl-client (repl-value1 "123")))))

(def-repl-test ^:tcp-and-unix transports-fail-on-disconnects
  (testing "Ensure that transports fail ASAP when the server they're connected to goes down."
    (transport/send transport {:op "eval" :code "(+ 1 1)"})

    (let [reader (future (while true (transport/recv transport)))]
      (Thread/sleep 100)
      (.close *server*)
      (Thread/sleep 100)
      (is (thrown-with-msg? Exception disconnected-re
                            (deref reader 1000 :timeout))))

    (is (thrown? SocketException (transport/recv transport)))
    ;; The next `Thread/sleep` is needed or the test would be fleaky
    ;; for some transports that don't throw an exception the first time
    ;; a message is sent after the server is closed.
    (try
      (transport/send transport {:op "eval" :code "(+ 5 1)"})
      (catch Exception _))
    (Thread/sleep 100)
    (is (thrown-with-msg? Exception disconnected-re
                          (transport/send transport {:op "eval" :code "(+ 5 1)"})))))

(deftest server-starts-with-minimal-configuration
  (testing "Ensure server starts with minimal configuration"
    (with-open [server (server/start-server)
                ^java.io.Closeable transport (nrepl/connect :port (:port server))]
      (let [client (nrepl/client transport Long/MAX_VALUE)]
        (is+ {:value "3"}
             (first (nrepl/message client {:op "eval" :code "(- 4 1)"})))))))

(def-repl-test clients-fail-on-disconnects
  (testing "Ensure that clients fail ASAP when the server they're connected to goes down."
    (let [resp (repl-eval client "1 2 3 4 5 6 7 8 9 10")]
      (is+ {:value "1"} (first resp))
      (.close *server*)
      (is (thrown-with-msg? Exception disconnected-re
                            (dotimes [_ 20]
                              (transport/recv transport)))))

    ;; TODO: as noted in transports-fail-on-disconnects, *sometimes* two sends are needed
    ;; to trigger an exception on send to an unavailable server
    (try (repl-eval! session "(+ 1 1)") (catch Throwable _))
    (Thread/sleep 100)
    (is (thrown-with-msg? Exception disconnected-re
                          (repl-eval session "(+ 1 1)")))))

(deftest url-connect
  (with-nrepl-server {:port 0}
    (with-open [^nrepl.transport.FnTransport
                conn (nrepl/url-connect (format "%s://127.0.0.1:%s"
                                                (:slug *transport*) (:port *server*)))]
      (let [cli (nrepl/client conn Long/MAX_VALUE)]
        (is (= 2 (eval-value1 cli '(+ 1 1))))))))

(deftest server-acks-its-port
  (with-open [s (server/start-server :transport-fn (:fn *transport*)
                                     :handler (-> (server/default-handler)
                                                  ack/handle-ack))]
    (ack/reset-ack-port!)
    (with-open [s2 (server/start-server :transport-fn (:fn *transport*)
                                        :ack-port (:port s))]
      (is (= (:port s2) (ack/wait-for-ack 10000))))))

(deftest wait-for-ack-gives-up
  (ack/reset-ack-port!)
  (is (nil? (ack/wait-for-ack 10))))

(deftest ack-reply-carries-the-request-id
  ;; send-ack waits for a reply to its own request, so one without the :id
  ;; held up every server started with :ack-port by the client's timeout.
  (ack/reset-ack-port!)
  (let [sent (atom [])]
    ((ack/handle-ack identity) {:op "ack" :id "42" :port 1234
                                :transport (transport/fn-transport nil #(swap! sent conj %))})
    (is (= [{:id "42" :status #{:done}}] @sent))))

(deftest connect-needs-somewhere-to-connect-to
  (is (thrown-with-msg? IllegalArgumentException #"host plus port or a socket"
                        (nrepl/connect)))
  (is (thrown? java.net.ConnectException
               (nrepl/connect :port (free-port)))))

(deftest url-connect-rejects-unknown-schemes
  (is (thrown-with-msg? IllegalArgumentException #"No nREPL support known for scheme gopher"
                        (nrepl/url-connect "gopher://localhost:7888"))))

(deftest failed-ack-stops-the-server
  (let [ack-port (free-port)
        port (free-port)]
    (is (thrown? java.net.ConnectException
                 (server/start-server :port port :ack-port ack-port)))
    (is (thrown? java.net.ConnectException
                 (nrepl/connect :port port)))))

(def-repl-test print-namespace-maps-binding
  (repl-eval! session "(set! *print-namespace-maps* true)")
  (is (true? (repl-value1 session "*print-namespace-maps*")))
  (repl-eval! session "(set! *print-namespace-maps* false)")
  (is (false? (repl-value1 session "*print-namespace-maps*"))))

;;;; Classloader-related tests

(defn classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip]
   (->> tip
        (iterate #(.getParent ^ClassLoader %))
        (take-while boolean))))

(def-repl-test common-classloader
  (testing "Check if RT/baseLoader and ContexClassLoader have a common DCL ancestor"
    (repl-eval! (code (nrepl.test-helpers/capture-state
                       "base-loader"
                       (nrepl.util.classloader/find-topmost-dcl (clojure.lang.RT/baseLoader)))))
    (repl-eval! (code (nrepl.test-helpers/capture-state
                       "ccl"
                       (nrepl.util.classloader/find-topmost-dcl (.getContextClassLoader (Thread/currentThread))))))
    (let [base-loader (get-state "base-loader")
          ccl (get-state "ccl")]
      (is base-loader)
      (is (= base-loader ccl)))))

(def-repl-test classloader-chain-doesnt-grow
  (testing "after doing regular evals, the classloader chain remains of the same length"
    (let [chain-length (repl-value1 (code (count
                                           (nrepl.core-test/classloader-hierarchy))))]
      ;; Eval some things.
      (dotimes [_ 10] (repl-eval! (code (+ 1 2))))
      (is (= chain-length
             (repl-value1 (code (count
                                 (nrepl.core-test/classloader-hierarchy)))))))))

(def-repl-test custom-context-classloader-is-not-overwritten
  (testing "if user eval code has set the custom context classloader, then it persists"
    (repl-eval!
     (code
      (let [t (Thread/currentThread)
            new-cl (clojure.lang.DynamicClassLoader.
                    (.getContextClassLoader t))]
        (.setContextClassLoader t new-cl)
        (nrepl.test-helpers/capture-state "new-cl" new-cl))))
    (is (repl-value1
         (code
          (contains? (set (#'nrepl.core-test/classloader-hierarchy))
                     (nrepl.test-helpers/get-state "new-cl")))))))

(def-repl-test custom-context-classloader-is-used-for-loading
  ;; Libraries like lambdaisland.classpath install their own classloader as the
  ;; session thread's context classloader. Loading during eval has to resolve
  ;; through it - binding Compiler/LOADER to a DynamicClassLoader ancestor
  ;; instead silently bypasses it (see #464).
  (testing "a custom context classloader ends up in the chain used for loading"
    (repl-eval! (code
                 (let [t (Thread/currentThread)
                       new-cl (clojure.lang.DynamicClassLoader.
                               (.getContextClassLoader t))]
                   (.setContextClassLoader t new-cl)
                   (nrepl.test-helpers/capture-state "custom-cl" new-cl))))
    (is (repl-value1
         (code
          (contains? (set (nrepl.core-test/classloader-hierarchy
                           (clojure.lang.RT/baseLoader)))
                     (nrepl.test-helpers/get-state "custom-cl")))))))
