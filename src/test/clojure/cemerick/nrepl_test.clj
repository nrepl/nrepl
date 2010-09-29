(ns cemerick.nrepl-test
  (:use clojure.test)
  (:require [cemerick.nrepl :as repl]
    [clojure.set :as set]))

(def *server-port* nil)

(defn repl-server-fixture
  [f]
  (let [[server-socket accept-future] (repl/start-server)]
    (try
      (binding [*server-port* (.getLocalPort server-socket)]
        (f))
      (finally (.close server-socket)))))

(use-fixtures :once repl-server-fixture)

(defmacro def-repl-test
  [name & body]
  `(deftest ~(with-meta name {:private true})
     (let [connection# (repl/connect *server-port*)
           ~'connection connection#
           ~'repl (:send connection#)
           ~'repl-receive (comp (fn [r#] (r#)) ~'repl)
           ~'repl-read (comp repl/read-response-value ~'repl-receive)
           ~'repl-value (comp :value ~'repl-read)]
       ~@body
       ((:close connection#)))))

(def-repl-test eval-literals
  (are [literal] (= literal (-> literal pr-str repl-value))
    5
    0xff
    5.1
    -2e12
    ;1/4
    :keyword
    ::local-ns-keyword
    :other.ns/keyword
    "string"
    "string\nwith\r\nlinebreaks"
    [1 2 3]
    {1 2 3 4}
    #{1 2 3 4}))

(def-repl-test simple-expressions
  (are [expression] (= (-> expression read-string eval) (repl-value expression))
    "'(1 2 3)"
    "'symbol"
    "(range 40)"
    "(apply + (range 100))"))

(def-repl-test defining-fns
  (repl-value "(defn foobar [] 6)")
  (is (= 6 (repl-value "(foobar)"))))

(def-repl-test repl-value-history
  (doall (map repl-value ["(apply + (range 6))" "(str 12 \\c)" "(keyword \"hello\")"]))
  (let [history [15 "12c" :hello]]
    (is (= history (repl-value "[*3 *2 *1]")))
    (is (= history (repl-value "*1")))))

(def-repl-test exceptions
  (let [{:keys [out status value] :as f} (repl-receive "(throw (Exception. \"bad, bad code\"))")]
    (is (= "error" status))
    (is (.contains value "bad, bad code"))
    (is (.contains out "bad, bad code"))
    (is (= true (repl-value "(.contains (str *e) \"bad, bad code\")")))))

(def-repl-test multiple-expressions-return
  (is (= 18 (repl-value "5 (/ 5 0) (+ 5 6 7)"))))

(def-repl-test return-on-incomplete-expr
  (let [{:keys [out status value]} (repl-read "(apply + (range 20)")]
    (is (nil? value))
    (is (= "error" status))))

(def-repl-test switch-ns
  (is (= "otherns" (:ns (repl-read "(ns otherns) (defn function [] 12)"))))
  (is (= 12 (repl-value "(otherns/function)"))))

(def-repl-test timeout
  (is (= "timeout" (:status (repl-read "(Thread/sleep 60000)" :timeout 1000)))))

(def-repl-test interrupt
  (let [resp (repl "(Thread/sleep 60000)")]
    (Thread/sleep 1000)
    (is (= "ok" (:status (resp :interrupt))))
    (is (= "interrupted" (:status (resp))))))

(def-repl-test verify-interrupt-on-timeout
  (let [resp (repl "(def a 0)(def a (apply + (iterate inc 0)))" :timeout 2000)]
    (is (= "timeout" (:status (resp))))
    (Thread/sleep 1000)
    (is (= 0 (repl-value "a")))))

(def-repl-test ensure-closeable
  (is (= 5 (repl-value "5")))
  (.close connection)
  (is (thrown? java.net.SocketException (repl-value "5"))))

(def-repl-test use-sent-*in*
  (is (= 6 (repl-value "(eval (read))" :in "(+ 1 2 3)"))))

(def-repl-test unordered-message-reads
  ; check that messages are being retained properly when read out of order
  (let [{:keys [send receive]} connection
        digits (range 10)
        response-fns (->> digits
                       (map (comp send str))
                       reverse)
        results (for [f response-fns] (f))]
    (is (= digits (->> results
                    (map (comp :value repl/read-response-value))
                    reverse)))))

; Testing java.lang.ref stuff is always tricky, but it's critical that promises
; associated with unreferenced response fns are being expired out of the WeakHashMap properly
(deftest response-promise-expiration
  (let [promises-map (#'repl/response-promises-map)]
    (binding [repl/response-promises-map (constantly promises-map)]
      (let [{:keys [send close]} (repl/connect *server-port*)]
        (doseq [response (->> (take 1000 (repeatedly #(send "5" :timeout 100)))
                           (pmap #(% 5000)))]
          (when-not (and (is (= "ok" (:status response)))
                    (is (= 5 (-> response repl/read-response-value :value))))
            ; fail fast if something is wrong to avoid waiting for 1000 failures
            (throw (Exception. (str "Failed response " response)))))
        
        (System/gc)
        (println "Should be less than 1,000:" (count promises-map))
        (is (< (count promises-map) 1000) "Response promises map has not been pruned at all; weak ref scheme (maybe) not working.")
        (close)))))

(def-repl-test ack
  (repl/reset-ack-port!)
  (let [server-process (.exec (Runtime/getRuntime)
                         (into-array ["java" "-Dnreplacktest=y" "-cp" (System/getProperty "java.class.path")
                                      "cemerick.nrepl.main" "--ack" (str *server-port*)]))
        acked-port (repl/wait-for-ack! 20000)]
    (try
      (is acked-port "Timed out waiting for ack")
      (when acked-port
        (with-open [c2 (repl/connect acked-port)]
          ; just a sanity check
          (is (= "y" (-> (((:send c2) "(System/getProperty \"nreplacktest\")")) repl/read-response-value :value)))))
      (finally
        (.destroy server-process)))))

(def-repl-test explicit-port-argument
  (repl/reset-ack-port!)
  (let [free-port (with-open [ss (java.net.ServerSocket.)]
                    (.bind ss nil)
                    (.getLocalPort ss))
        server-process (.exec (Runtime/getRuntime)
                         (into-array ["java" "-Dnreplacktest=y" "-cp" (System/getProperty "java.class.path")
                                      "cemerick.nrepl.main" "--port" (str free-port) "--ack" (str *server-port*)]))
        acked-port (repl/wait-for-ack! 20000)]
    (try
      (is acked-port "Timed out waiting for ack")
      (is (= acked-port free-port))
      (finally
        (.destroy server-process)))))
