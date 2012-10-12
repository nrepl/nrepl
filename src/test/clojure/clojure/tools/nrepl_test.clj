(ns clojure.tools.nrepl-test
  (:import java.net.SocketException)
  (:use clojure.test
        clojure.tools.nrepl)
  (:require (clojure.tools.nrepl [transport :as transport]
                                 [server :as server]
                                 [ack :as ack])))

(def ^{:dynamic true} *server* nil)

(defn repl-server-fixture
  [f]
  (with-open [server (server/start-server)]
    (binding [*server* server]
      (f))))

(use-fixtures :each repl-server-fixture)

(defmacro def-repl-test
  [name & body]
  `(deftest ~(with-meta name {:private true})
     (with-open [transport# (connect :port (:port *server*))]
       (let [~'transport transport#
             ~'client (client transport# Long/MAX_VALUE)
             ~'session (client-session ~'client)
             ~'timeout-client (client transport# 1000)
             ~'timeout-session (client-session ~'timeout-client)
             ~'repl-eval #(message % {:op :eval :code %2})
             ~'repl-values (comp response-values ~'repl-eval)]
         ~@body))))

(def-repl-test eval-literals
  (are [literal] (= (binding [*ns* (find-ns 'user)] ; needed for the ::keyword
                      (-> literal read-string eval list))
                    (repl-values client literal))
    "5"
    "0xff"
    "5.1"
    "-2e12"
    "1/4"
    "'symbol"
    "'namespace/symbol"
    ":keyword"
    "::local-ns-keyword"
    ":other.ns/keyword"
    "\"string\""
    "\"string\\nwith\\r\\nlinebreaks\""
    "'(1 2 3)"
    "[1 2 3]"
    "{1 2 3 4}"
    "#{1 2 3 4}")

  (is (= (->> "#\"regex\"" read-string eval list (map str))
         (->> "#\"regex\"" (repl-values client) (map str)))))

(def-repl-test simple-expressions
  (are [expr] (= [(eval expr)] (repl-values client (pr-str expr)))
    '(range 40)
    '(apply + (range 100))))

(def-repl-test defining-fns
  (repl-values client "(defn x [] 6)")
  (is (= [6] (repl-values client "(x)"))))

(def-repl-test unknown-op
  (is (= {:op "abc" :status #{"error" "unknown-op" "done"}}
         (-> (message timeout-client {:op :abc}) combine-responses (select-keys [:op :status])))))

(def-repl-test session-lifecycle
  (is (= #{"error" "unknown-session"}
         (-> (message timeout-client {:session "abc"}) combine-responses :status)))
  (let [session-id (new-session timeout-client)
        session-alive? #(contains? (-> (message timeout-client {:op :ls-sessions})
                                     combine-responses
                                     :sessions
                                     set)
                                   session-id)]
    (is session-id)
    (is (session-alive?))
    (is (= #{"done" "session-closed"} (-> (message timeout-client {:op :close :session session-id})
                                        combine-responses
                                        :status)))
    (is (not (session-alive?)))))

(def-repl-test separate-value-from-*out*
  (is (= {:value [nil] :out "5\n"}
         (-> (map read-response-value (repl-eval client "(println 5)"))
           combine-responses
           (select-keys [:value :out])))))

(def-repl-test streaming-out
  (is (= (for [x (range 10)]
           (str x \newline))
        (->> (repl-eval client "(dotimes [x 10] (println x))")
          (map :out)
          (remove nil?)))))

(def-repl-test streaming-out-without-explicit-flushing
  (is (= ["(0 1 "
          "2 3 4"
          " 5 6 "
          "7 8 9"
          " 10)"]
         ; new session
         (->> (message client {:op :eval :out-limit 5 :code "(print (range 11))"})
              (map :out)
              (remove nil?))
         ; existing session
         (->> (message session {:op :eval :out-limit 5 :code "(print (range 11))"})
              (map :out)
              (remove nil?)))))

(def-repl-test ensure-whitespace-prints
  (is (= " \t \n \f \n" (->> (repl-eval client "(println \" \t \n \f \")")
                          combine-responses
                          :out))))

(def-repl-test session-return-recall
  (repl-eval session (code
                       (apply + (range 6))
                       (str 12 \c)
                       (keyword "hello")))
  (let [history [[15 "12c" :hello]]]
    (is (= history (repl-values session "[*3 *2 *1]")))
    (is (= history (repl-values session "*1"))))

  (is (= [nil] (repl-values client "*1"))))

(def-repl-test session-set!
  (repl-eval session (code
                       (set! *compile-path* "badpath")
                       (set! *warn-on-reflection* true)))
  (is (= [["badpath" true]] (repl-values session (code [*compile-path* *warn-on-reflection*])))))

(def-repl-test exceptions
  (let [{:keys [status err value]} (combine-responses (repl-eval session "(throw (Exception. \"bad, bad code\"))"))]
    (is (= #{"eval-error" "done"} status))
    (is (nil? value))
    (is (.contains err "bad, bad code"))
    (is (= [true] (repl-values session "(.contains (str *e) \"bad, bad code\")")))))

(def-repl-test multiple-expressions-return
  (is (= [5 18] (repl-values session "5 (/ 5 0) (+ 5 6 7)"))))

(def-repl-test return-on-incomplete-expr
  (let [{:keys [out status value]} (combine-responses (repl-eval session "(missing paren"))]
    (is (nil? value))
    (is (= #{"done" "eval-error"} status))
    (is (re-seq #"EOF while reading" (first (repl-values session "(.getMessage *e)"))))))

(def-repl-test switch-ns
  (is (= "otherns" (-> (repl-eval session "(ns otherns) (defn function [] 12)")
                     combine-responses
                     :ns)))
  (is (= [12] (repl-values session "(function)")))
  (repl-eval session "(in-ns 'user)")
  (is (= [12] (repl-values session "(otherns/function)"))))

(def-repl-test switch-ns
  (is (= "otherns" (-> (repl-eval session (code
                                            (ns otherns)
                                            (defn function [] 12)))
                     combine-responses
                     :ns)))
  (is (= [12] (repl-values session "(function)")))
  (repl-eval session "(in-ns 'user)")
  (is (= [12] (repl-values session "(otherns/function)")))
  (is (= "user" (-> (repl-eval session "nil") combine-responses :ns))))

(def-repl-test explicit-ns
  (is (= "user" (-> (repl-eval session "nil") combine-responses :ns)))
  (is (= "baz" (-> (repl-eval session (code
                                        (def bar 5)
                                        (ns baz)))
                 combine-responses
                 :ns)))
  (is (= [5] (response-values (message session {:op :eval :code "bar" :ns "user"})))))

(def-repl-test error-on-nonexistent-ns
  (is (= #{"error" "namespace-not-found" "done"}
         (-> (message timeout-client {:op :eval :code "(+ 1 1)" :ns (name (gensym))})
           combine-responses
           :status))))

(def-repl-test proper-response-ordering
  (is (= [[nil "100\n"] ; printed number
          ["nil" nil] ; return val from println
          ["42" nil]  ; return val from `42`
          [nil nil]]  ; :done
         (map (juxt :value :out) (repl-eval client "(println 100) 42")))))

(def-repl-test interrupt
  (is (= #{"error" "interrupt-id-mismatch" "done"}
         (-> (message client {:op :interrupt :interrupt-id "foo"})
           first
           :status
           set)))

  (let [resp (message session {:op :eval :code (code (do
                                                       (def halted? true)
                                                       halted?
                                                       (Thread/sleep 30000)
                                                       (def halted? false)))})]
    (Thread/sleep 100)
    (is (= #{"done"} (-> session (message {:op :interrupt}) first :status set)))
    (is (= #{"done" "interrupted"} (-> resp combine-responses :status)))
    (is (= [true] (repl-values session "halted?")))))

(def-repl-test read-timeout
  (is (nil? (repl-values timeout-session "(Thread/sleep 1100) :ok")))
  ; just getting the values off of the wire so the server side doesn't
  ; toss a spurious stack trace when the client disconnects
  (is (= [nil :ok] (->> (repeatedly #(transport/recv transport 500))
                     (take-while (complement nil?))
                     response-values))))

(def-repl-test concurrent-message-handling
  (testing "multiple messages can be handled on the same connection concurrently"
    (let [sessions (doall (repeatedly 3 #(client-session client)))
          start-time (System/currentTimeMillis)
          elapsed-times (map (fn [session eval-duration]
                               (let [expr (pr-str `(Thread/sleep ~eval-duration))
                                     responses (message session {:op :eval :code expr})]
                                 (future
                                   (is (= [nil] (response-values responses)))
                                   (- (System/currentTimeMillis) start-time))))
                             sessions
                             [2000 1000 0])]
      (is (apply > (map deref (doall elapsed-times)))))))

(def-repl-test ensure-transport-closeable
  (is (= [5] (repl-values session "5")))
  (is (instance? java.io.Closeable transport))
  (.close transport)
  (is (thrown? java.net.SocketException (repl-values session "5"))))

; test is flaking on hudson, but passing locally! :-X
#_(def-repl-test ensure-server-closeable
  (.close *server*)
  (is (thrown? java.net.ConnectException (connect :port (:port *server*)))))

; wasn't added until Clojure 1.3.0
(defn- root-cause
  "Returns the initial cause of an exception or error by peeling off all of
  its wrappers"
  [^Throwable t]
  (loop [cause t]
    (if-let [cause (.getCause cause)]
      (recur cause)
      cause)))

(defn- disconnection-exception?
  [e]
  ; thrown? should check for the root cause!
  (and (instance? SocketException (root-cause e))
       (re-matches #".*lost.*connection.*" (.getMessage (root-cause e)))))

(deftest transports-fail-on-disconnects
  (testing "Ensure that transports fail ASAP when the server they're connected to goes down."
    (let [server (server/start-server)
          transport (connect :port (:port server))]
      (transport/send transport {"op" "eval" "code" "(+ 1 1)"})
      
      (let [reader (future (while true (transport/recv transport)))]
        (Thread/sleep 1000)
        (.close server)
        (Thread/sleep 1000)
        ; no deref with timeout in Clojure 1.2.0 :-(
        (try
          (.get reader 10000 java.util.concurrent.TimeUnit/MILLISECONDS)
          (is false "A reader started prior to the server closing should throw an error...")
          (catch Throwable e
            (is (disconnection-exception? e)))))
      
      (is (thrown? SocketException (transport/recv transport)))
      ;; TODO no idea yet why two sends are *sometimes* required to get a failure
      (try
        (transport/send transport {"op" "eval" "code" "(+ 5 1)"})
        (catch Throwable t))
      (is (thrown? SocketException (transport/send transport {"op" "eval" "code" "(+ 5 1)"}))))))

(def-repl-test clients-fail-on-disconnects
  (testing "Ensure that clients fail ASAP when the server they're connected to goes down."
    (let [resp (repl-eval client "1 2 3 4 5 6 7 8 9 10")]
      (is (= "1" (-> resp first :value)))
      (Thread/sleep 1000)
      (.close *server*)
      (Thread/sleep 1000)
      (try
        ; these responses were on the wire before the remote transport was closed
        (is (> 20 (count resp)))
        (transport/recv transport)
        (is false "reads after the server is closed should fail")
        (catch Throwable t
          (is (disconnection-exception? t)))))
    
    ;; TODO as noted in transports-fail-on-disconnects, *sometimes* two sends are needed
    ;; to trigger an exception on send to an unavailable server
    (try (repl-eval session "(+ 1 1)") (catch Throwable t))
    (is (thrown? SocketException (repl-eval session "(+ 1 1)")))))

(def-repl-test request-*in*
  (is (= '((1 2 3)) (response-values (for [resp (repl-eval session "(read)")]
                                       (do
                                         (when (-> resp :status set (contains? "need-input"))
                                           (session {:op :stdin :stdin "(1 2 3)"}))
                                         resp)))))

  (session {:op :stdin :stdin "a\nb\nc\n"})
  (doseq [x "abc"]
    (is (= [(str x)] (repl-values session "(read-line)")))))

(def-repl-test request-multiple-read-newline-*in*
  (is (= '(:ohai) (response-values (for [resp (repl-eval session "(read)")]
                                       (do
                                         (when (-> resp :status set (contains? "need-input"))
                                           (session {:op :stdin :stdin ":ohai\n"}))
                                         resp)))))

  (session {:op :stdin :stdin "a\n"})
  (is (= ["a"] (repl-values session "(read-line)"))))

(def-repl-test request-multiple-read-objects-*in*
  (is (= '(:ohai) (response-values (for [resp (repl-eval session "(read)")]
                                       (do
                                         (when (-> resp :status set (contains? "need-input"))
                                           (session {:op :stdin :stdin ":ohai :kthxbai\n"}))
                                         resp)))))

  (is (= [" :kthxbai"] (repl-values session "(read-line)"))))

(def-repl-test test-url-connect
  (with-open [conn (url-connect (str "nrepl://localhost:" (:port *server*)))]
    (transport/send conn {:op :eval :code "(+ 1 1)"})
    (is (= [2] (response-values (response-seq conn 100))))))

(deftest test-ack
  (with-open [s (server/start-server :handler (ack/handle-ack (server/default-handler)))]
    (ack/reset-ack-port!)
    (with-open [s2 (server/start-server :ack-port (:port s))]
      (is (= (:port s2) (ack/wait-for-ack 10000))))))

(def-repl-test agent-await
  (is (= [42] (repl-values session (code (let [a (agent nil)]
                                           (send a (fn [_] (Thread/sleep 1000) 42))
                                           (await a)
                                           @a))))))
