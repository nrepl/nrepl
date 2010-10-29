(ns #^{:doc ""
       :author "Chas Emerick"}
  clojure.tools.nrepl-test
  (:use clojure.test)
  (:require [clojure.tools.nrepl :as repl]
    [clojure.set :as set]))

(println (format "Testing with Clojure v%s" (clojure-version)))
(println (str "Clojure contrib available? " (or (try
                                                  (require 'clojure.contrib.core)
                                                  "yes"
                                                  (catch Throwable t))
                                              "no")))
(println (str "Pretty-printing available? " (repl/pretty-print-available?)))

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
           ~'repl-seq (comp repl/response-seq ~'repl)
           ~'repl-receive (comp (fn [r#] (r#)) ~'repl)
           ~'repl-read (comp repl/read-response-value ~'repl-receive)
           ~'repl-value (comp :value ~'repl-read)]
       ~@body
       ((:close connection#)))))

(defn- full-response
  [response-fn]
  (->> response-fn
    repl/response-seq
    (map repl/read-response-value)
    repl/combine-responses))

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

(def-repl-test separate-value-from-*out*
  (let [{:keys [out value]} (->> (repl-seq "(println 5)")
                              (map repl/read-response-value)
                              repl/combine-responses)]
    (is (nil? (first value)))
    (is (= "5" (.trim out)))))

(def-repl-test streaming-out
  (is (= (for [x (range 10)]
           (str x \newline))
        (->> (repl "(dotimes [x 10] (println x))")
          repl/response-seq
          (map :out)
          (remove nil?)))))

(def-repl-test defining-fns
  (repl-value "(defn foobar [] 6)")
  (is (= 6 (repl-value "(foobar)"))))

(def-repl-test repl-value-history
  (doall (map repl-value ["(apply + (range 6))" "(str 12 \\c)" "(keyword \"hello\")"]))
  (let [history [15 "12c" :hello]]
    (is (= history (repl-value "[*3 *2 *1]")))
    (is (= history (repl-value "*1")))))

(def-repl-test exceptions
  (let [{:keys [err status value] :as f} (full-response (repl "(throw (Exception. \"bad, bad code\"))"))]
    (is (= #{"error" "done"} status))
    (is (nil? value))
    (is (.contains err "bad, bad code"))
    (is (= true (repl-value "(.contains (str *e) \"bad, bad code\")")))))

(def-repl-test auto-print-stack-trace
  (is (= true (repl-value "(set! clojure.tools.nrepl/*print-stack-trace-on-error* true)")))
  (is (.contains (-> (repl "(throw (Exception. \"foo\" (Exception. \"nested exception\")))")
                   full-response
                   :err)
        "nested exception")))

(def-repl-test multiple-expressions-return
  (is (= [5 18] (->> (repl-seq "5 (/ 5 0) (+ 5 6 7)")
                  (map repl/read-response-value)
                  repl/combine-responses
                  :value))))

(def-repl-test return-on-incomplete-expr
  (let [{:keys [out status value]} (full-response (repl "(apply + (range 20)"))]
    (is (nil? value))
    (is (= #{"done" "error"} status))))

(def-repl-test throw-on-unreadable-return
  (is (thrown-with-msg? Exception #".*#<ArrayList \[\]>.*"
        (full-response (repl "(java.util.ArrayList.)")))))

(def-repl-test switch-ns
  (is (= "otherns" (:ns (repl-read "(ns otherns) (defn function [] 12)"))))
  (is (= [12] (repl/values-with connection (otherns/function)))))

(def-repl-test timeout
  (is (= "timeout" (:status (repl-read "(Thread/sleep 60000)" :timeout 1000)))))

(def-repl-test interrupt==halt
  ; tests interrupts as well as ensures that the interrupt is halting all further output
  (let [resp (repl "(def halted? true)(Thread/sleep 6000)(def halted? false)")]
    (Thread/sleep 1000)
    (is (= true (-> (resp :interrupt) repl/read-response-value :value)))
    (let [resp (repl/response-seq resp 8000)]
      (is (= 2 (count resp)))
      (is (= "interrupted" (-> resp second :status)))
      (is (= true (repl-value "halted?"))))))

(def-repl-test verify-interrupt-on-timeout
  (let [resp (repl "(def a 0)(def a (do (Thread/sleep 3000) 1))" :timeout 1000)]
    (is (:value (resp)))
    (is (= "timeout" (:status (resp))))
    (Thread/sleep 5000)
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
        (doseq [response (->> (repeatedly #(send "5" :timeout 1000))
                           (take 1000)
                           (pmap #(->> (repl/response-seq % 5000)
                                    (map repl/read-response-value)
                                    repl/combine-responses)))]
          (when-not (and (is (= #{"done"} (:status response)))
                      (is (= [5] (:value response))))
            ; fail fast if something is wrong to avoid waiting for 1000 failures
            (throw (Exception. (str "Failed response " response)))))
        
        (System/gc)
        (println "Should be less than 1,000:" (count promises-map))
        (is (< (count promises-map) 1000) "Response promises map has not been pruned at all; weak ref scheme (maybe) not working.")
        (close)))))

(defn- redirect-process-output
  [process]
  (future (try
            (let [out (-> process .getInputStream java.io.InputStreamReader. java.io.BufferedReader.)
                  err (-> process .getErrorStream java.io.InputStreamReader. java.io.BufferedReader.)]
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err))
              (println (.readLine err)))
            (catch Throwable t
              (.printStackTrace t)))))

(def-repl-test ack
  (repl/reset-ack-port!)
  (let [server-process (.exec (Runtime/getRuntime)
                         (into-array ["java" "-Dnreplacktest=y" "-cp" (System/getProperty "java.class.path")
                                      "clojure.tools.nrepl.main" "--ack" (str *server-port*)]))
        acked-port (repl/wait-for-ack 20000)]
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
                                      "clojure.tools.nrepl.main" "--port" (str free-port) "--ack" (str *server-port*)]))
        acked-port (repl/wait-for-ack 20000)]
    (try
      (is acked-port "Timed out waiting for ack")
      (is (= acked-port free-port))
      (finally
        (.destroy server-process)))))

(deftest repl-out-writer
  (let [responses (atom [])
        [w-agent w] (#'repl/create-repl-out :out #(swap! responses conj %&))]
    (doto w
      .flush
      (.write "abcd")
      (.write (.toCharArray "ef") 0 2)
      (.write "gh" 0 2)
      (.write (.toCharArray "ij"))
      (.write 32)
      .flush
      .flush
      (.write "no writes\nkeyed on linebreaks")
      .flush)
    (with-open [out (java.io.PrintWriter. w)]
      (binding [*out* out]
        (newline)
        (prn #{})
        (flush)))
    
    (await w-agent)
    (is (= [[:out "abcdefghij "]
            [:out "no writes\nkeyed on linebreaks"]
            [:out "\n#{}\n"]]
          @responses))))

(def-repl-test repl-literal
  (let [deffn (repl/send-with connection
                (ns send-with-test)
                (defn as-seqs
                  [& colls]
                  (map seq colls)))]
    (is (= #{"done"} (-> deffn repl/response-seq repl/combine-responses :status)))
    (is (= (take 3 (repeat [1 2 3]))
          (-> (repl/send-with connection
                 (send-with-test/as-seqs
                   '(1 2 3) [1 2 3] (into (sorted-set) #{1 2 3})))
             full-response
             :value
             first)))))

(def-repl-test eval-literal
  (is (= [5] (repl/values-with connection 5)))
  (is (= [5 124750] (repl/values-with connection 5 (apply + (range 500))))))

(def-repl-test retained-session
  (let [session-id (-> (repl/send-with connection
                         (clojure.tools.nrepl/retain-session!))
                     full-response
                     :value
                     first)
        resp (with-open [c2 (repl/connect *server-port*)]
               (full-response ((:send c2)
                      "(throw (Exception. \"retainedsession\")) (range 5) :foo {:a 5}"
                      :session-id session-id)))]
    (is session-id)
    (is (.contains (:err resp) "retainedsession"))
    (is (= (:value resp) [(range 5) :foo {:a 5}]))
    (is (= #{"done" "error"} (:status resp)))
    
    (let [[[ex & restvals]] (with-open [c2 (repl/connect *server-port*)]
                              (-> ((:send c2) "[(str *e) *3 *2 *1]" :session-id session-id)
                                full-response
                                :value))]
      (is (.contains ex "retainedsession"))
      (is (= restvals [(range 5) :foo {:a 5}])))
    
    (is (with-open [c2 (repl/connect *server-port*)]
          (->> ((:send c2) "(clojure.tools.nrepl/release-session!)" :session-id session-id)
            full-response
            :value
            first)))
    
    (is (nil? (with-open [c2 (repl/connect *server-port*)]
                (->> ((:send c2) "*e" :session-id session-id)
                  full-response
                  :value
                  first))))))

(def-repl-test explicit-ns
  (= "baz" (-> (repl/send-with connection
                 (def bar 5)
                 (ns baz))
             full-response
             :ns))
  (= 5 (repl-value "bar" :ns "user")))