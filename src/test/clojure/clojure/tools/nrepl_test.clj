(ns clojure.tools.nrepl-test
  (:use clojure.test
        clojure.tools.nrepl
        [clojure.tools.nrepl.server :only (start-server)])
  (:require [clojure.tools.nrepl.transport :as transport]))

(def ^{:dynamic true} *server-port* nil)

(defn repl-server-fixture
  [f]
  (with-open [server (start-server)]
    (binding [*server-port* (.getLocalPort (:ss @server))]
      (f))))

(use-fixtures :once repl-server-fixture)

(defn- response-values
  [repl-responses]
  (->> repl-responses combine-responses :value (map read-string)))

(defmacro def-repl-test
  [name & body]
  `(deftest ~(with-meta name {:private true})
     (with-open [transport# (connect :port *server-port*)]
       (let [~'transport transport#
             ~'client (client transport# 10000)
             ~'session (client-session ~'client)
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
  (is (= {:op "abc" :status #{"error" "unknown-op"}}
         (-> (message client {:op :abc}) combine-responses (select-keys [:op :status])))))

(def-repl-test session-lifecycle
  (is (= #{"error" "unknown-session"}
         (-> (message client {:session "abc"}) combine-responses :status)))
  (let [session-id (new-session client)
        session-alive? #(contains? (-> (message client {:op :ls-sessions})
                                     combine-responses
                                     :sessions
                                     set)
                                   session-id)]
    (is session-id)
    (is (session-alive?))
    (is (= #{"done" "session-closed"} (-> (message client {:op :close :session session-id})
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
    (is (= #{"error" "done"} status))
    (is (nil? value))
    (is (.contains err "bad, bad code"))
    (is (= [true] (repl-values session "(.contains (str *e) \"bad, bad code\")")))))

(def-repl-test multiple-expressions-return
  (is (= [5 18] (repl-values session "5 (/ 5 0) (+ 5 6 7)"))))

(def-repl-test return-on-incomplete-expr
  (let [{:keys [out status value]} (combine-responses (repl-eval session "(missing paren"))]
    (is (nil? value))
    (is (= #{"done" "error"} status))
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

(def-repl-test proper-response-ordering
  (is (= [[nil "100\n"] ; printed number
          ["nil" nil] ; return val from println
          ["42" nil]  ; return val from `42`
          [nil nil]]    ; :done message
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
    (is (= #{"done"} (-> (message session {:op :interrupt})
                      first :status set)))
    (is (= #{"done" "interrupted"} (-> resp combine-responses :status)))
    (is (= [true] (repl-values session "halted?")))))

(def-repl-test read-timeout
  (is (= [] (repl-values session "(Thread/sleep 11000)"))))

(def-repl-test ensure-closeable
  (is (= [5] (repl-values session "5")))
  (.close transport)
  (is (thrown? java.net.SocketException (repl-values session "5"))))

(def-repl-test request-*in*
  (is (= '((1 2 3)) (response-values (for [resp (repl-eval session "(read)")]
                                       (do
                                         (when (-> resp :status set (contains? "need-input"))
                                           (session {:op :stdin :stdin "(1 2 3)"}))
                                         resp)))))
  
  (session {:op :stdin :stdin "a\nb\nc\n"})
  (doseq [x "abc"]
    (is (= [(str x)] (repl-values session "(read-line)")))))

(def-repl-test test-url-connect
  (with-open [conn (url-connect (str "nrepl://localhost:" *server-port*))]
    (transport/send conn {:op :eval :code "(+ 1 1)"})
    (is (= [2] (response-values (response-seq conn 100))))))
