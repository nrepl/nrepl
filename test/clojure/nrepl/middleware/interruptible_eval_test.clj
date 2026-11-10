(ns nrepl.middleware.interruptible-eval-test
  {:author "Oleksandr Yakushev"}
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.core :as nrepl :refer [code]]
   [nrepl.test-helpers :refer :all]))

(def-repl-test eval-literals
  (are [literal] (= (binding [*ns* (find-ns 'user)] ; needed for the ::keyword
                      (-> literal read-string eval))
                    (repl-value1 literal))
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

  (is (= (str #"regex")
         (str (repl-value1 "#\"regex\"")))))

(def-repl-test simple-expressions
  (are [expr] (= expr (repl-value1 (code expr)))
    (range 40)
    (apply + (range 100))))

(def-repl-test defining-fns
  (repl-eval! (code (defn x [] 6)))
  (is (= 6 (repl-value1 (code (x))))))

(defn- dumb-alternative-eval
  [form]
  (let [result (eval form)]
    (if (number? result)
      (- result)
      result)))

(def-repl-test use-alternative-eval-fn
  (is+ {:value ["-124750"]}
       (message! {:op "eval"
                  :eval "nrepl.middleware.interruptible-eval-test/dumb-alternative-eval"
                  :code (code (reduce + (range 500)))})))

(def-repl-test source-tracking-eval
  (let [sym (name (gensym))]
    (message! {:op "eval" :ns "user" :code (format "(def %s 1000)" sym)
               :file "test.clj" :line 42 :column 10})
    (is+ {:file "test.clj", :line 42, :column 10}
         (meta (resolve (symbol "user" sym))))))

(def-repl-test no-code
  (is+ {:status #{:error :no-code :done}}
       (message! session {:op "eval"})))

(def-repl-test separate-value-from-*out*
  (is+ {:value ["nil"] :out "5\n"}
       (repl-eval! (code (println 5))))

  (testing "out works correctly both with a session or without"
    (is+ {:out "5\n:foo\n"}
         (repl-eval! client (code (println 5) (println :foo))))

    (is+ {:out "5\n:foo\n"}
         (repl-eval! session (code (println 5) (println :foo))))))

(def-repl-test error-on-lazy-seq-with-side-effects
  (is+ {:out "1\n"
        :err #"oops"}
       (repl-eval! (code
                    (let [foo (fn [] (map (fn [x]
                                            (println x)
                                            (throw (Exception. "oops")))
                                          [1 2 3]))]
                      (foo))))))

(def-repl-test proper-response-ordering
  (is+ [{:value mc/absent, :out "100\n"}                      ; printed number
        {:value "nil", :out mc/absent}                        ; return val from println
        {:value "42", :out mc/absent}                         ; return val from `42`
        {:value mc/absent, :out mc/absent, :status #{:done}}] ; :done
       (repl-eval session (code (println 100) 42))))

(def-repl-test exceptions
  (is+ {:status #{:eval-error :done}
        :value  mc/absent
        :err    #"bad, bad code"}
       (repl-eval! (code (throw (Exception. "bad, bad code")))))
  (is (= true (repl-value1 "(.contains (str *e) \"bad, bad code\")"))))

(def-repl-test multiple-expressions-return
  (testing "evaluation stops at the first error"
    (is (= [5 18] (repl-values "5 (+ 5 6 7) (/ 5 0)")))
    (is (= [5] (repl-values "5 (/ 5 0) (+ 5 6 7)")))
    (is (= [] (repl-values "(/ 5 0) 5 (+ 5 6 7)")))))

(def-repl-test return-on-incomplete-expr
  (is+ {:status #{:done :eval-error}, :value mc/absent}
       (repl-eval! "(missing paren"))
  (is+ #"EOF while reading"
       (repl-value1 "(-> *e Throwable->map :cause)")))

(def-repl-test switch-ns
  (is+ {:ns "otherns"}
       (repl-eval! (code
                    (ns otherns)
                    (defn function [] 12))))
  (is (= 12 (repl-value1 "(function)")))
  (repl-eval! (code (in-ns 'user)))
  (is+ {:value ["12"], :ns "user"}
       (repl-eval! (code (otherns/function))))
  (is+ {:ns "user"} (repl-eval! "nil")))

(def-repl-test explicit-ns
  (is+ {:ns "user"} (repl-eval! "nil"))
  (is+ {:ns "baz"} (repl-eval! (code
                                (def bar 5)
                                (ns baz))))
  (is+ {:value ["5"]} (message! {:op "eval" :code "bar" :ns "user"}))
  ;; NREPL-72: :ns argument to eval shouldn't affect *ns* outside of the scope of that evaluation
  (is+ {:ns "baz"} (repl-eval! "5")))

(def-repl-test error-on-nonexistent-ns
  (is+ {:status #{:error :namespace-not-found :done}}
       (message! session {:op "eval" :code "(+ 1 1)" :ns (name (gensym))})))

(def-repl-test agent-await
  (is+ 42 (repl-value1 (code (let [a (agent nil)]
                               (send a (fn [_] (Thread/sleep 100) 42))
                               (await a)
                               @a)))))

(def-repl-test printlns
  (is+ {:out "#inst \"2013-02-11T12:13:44.000+00:00\"\n"}
       (repl-eval! (code (println (doto (java.util.GregorianCalendar. 2013 1 11 12 13 44)
                                    (.setTimeZone (java.util.TimeZone/getTimeZone "GMT")))))))

  (is+ (conj (vec (for [x (range 10)]
                    {:out (str x "\n")}))
             {:value "nil"}
             {:status #{:done}})
       (repl-eval session (code (dotimes [x 10] (println x))))))

(def-repl-test stdout-stderr
  (is+ {:ns "user" :out "5 6 7 \n 8 9 10\n" :value ["nil"]}
       (repl-eval! (code (println 5 6 7 \newline 8 9 10))))
  (is+ {:ns "user" :err "user/foo\n" :value ["nil"]}
       (repl-eval! (code (binding [*out* *err*] (prn 'user/foo)))))
  (is+ {:ns "user" :err #"problem" :value [":value"]}
       (repl-eval! (code (do (.write *err* "problem") :value))))
  (is+ {:err #"Divide by zero" :value mc/absent}
       (repl-eval! client (code (/ 1 0)))))

(def-repl-test reader-conditional-option
  (is+ {:value ["#?(:clj (+ 1 2) :cljs (+ 2 3))"]}
       (message! client {:op "eval" :read-cond :preserve
                         :code "#?(:clj (+ 1 2) :cljs (+ 2 3))"}))
  (is+ {:value ["3"]}
       (message! client {:op "eval" :code "#?(:clj (+ 1 2) :cljs (+ 2 3))"})))

(def-repl-test ensure-whitespace-prints
  (is+ {:out " \t \n \f \n"}
       (repl-eval! "(println \" \t \n \f \")")))

(def-repl-test read-error-short-circuits-execution
  (testing "read error prevents the remaining code from being read and executed"
    (is+ {:out mc/absent
          :value mc/absent
          :err #"(?s)^Syntax error reading source at[^\n]+[\r]?\nMap literal must contain an even number of forms[\r]?\n"}
         (repl-eval! "{:a} (println \"BOOM!\")")))

  (testing "exactly one read error is produced even if there is remaining code in the message"
    (is+ {:out mc/absent
          :value mc/absent
          :err (mc/all-of #"Unmatched delimiter: \)"
                          (mc/mismatch #"Unmatched delimiter: \]")
                          (mc/mismatch #"Unmatched delimiter: \}"))}
         (repl-eval! ")]} 42"))))

(defn- find-frame-ending-with [^Throwable ex, suffix]
  (some #(when (str/ends-with? (.getClassName ^StackTraceElement %) suffix) %)
        (.getStackTrace ex)))

(def-repl-test preserves-source-location-test
  (testing "plain eval remembers source file for the compiled function"
    (repl-eval! (code (in-ns 'nrepl.middleware.interruptible-eval-test)))
    (message! {:op "eval" :code "(defn div0 [] (/ 1 0))"
               :file "src/acme/foo.clj" :line 42})
    (repl-eval! (code (div0)))
    (is+ "foo.clj" (repl-value1 (code (.getFileName (find-frame-ending-with *e "div0")))))))
