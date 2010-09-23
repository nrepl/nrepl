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

(def send-wait-read (comp repl/read-response-value repl/send-and-wait))

(defmacro def-repl-test
  [name & body]
  `(deftest ~(with-meta name {:private true})
     (let [connection# (repl/connect "localhost" *server-port*)
           ~'connection connection#
           ~'repl (partial repl/send-and-wait connection#)
           ~'repl-read (partial send-wait-read connection#)
           ~'repl-value (partial (comp :value send-wait-read) connection#)]
       ~@body)))

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
  (let [{:keys [out status value]} (repl-read "(throw (Exception. \"bad, bad code\"))")]
    (is (= "error" status))
    (is (nil? value))
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
  (let [req-id ((:send connection) "(Thread/sleep 60000)")
        _ (Thread/sleep 1000)
        cancel-id ((:send connection) (str "(cemerick.nrepl/interrupt \"" req-id "\")"))]
    (loop [ids #{req-id cancel-id}]
      (if-not (empty? ids)
        (let [{:keys [status id]} ((:receive connection))]
          (is (= status (if (= id req-id)
                          "cancelled"
                          "ok")))
          (recur (set/difference ids #{id})))))))