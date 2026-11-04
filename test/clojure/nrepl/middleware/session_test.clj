(ns nrepl.middleware.session-test
  {:author "Oleksandr Yakushev"}
  (:require
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.config :as config]
   [nrepl.core :as nrepl :refer [code]]
   [nrepl.test-helpers :refer :all]
   [nrepl.util.session :as session-util]))

(def ^:dynamic *test-var1*)
(def ^:dynamic *test-var2* :root)

(deftest dynvar-defaults-test
  (testing "dynvar receives a default value from config"
    (with-redefs [config/config {:dynamic-vars {'nrepl.middleware.session-test/*test-var1* 1}}]
      (with-nrepl-server {}
        (with-nrepl-client
          (is+ 1 (repl-value1 (code nrepl.middleware.session-test/*test-var1*)))))))

  (testing "config can override a dynvar that has a root value"
    (with-nrepl-server {}
      (with-nrepl-client
        (is+ :root (repl-value1 (code nrepl.middleware.session-test/*test-var2*)))))

    (with-redefs [config/config {:dynamic-vars {'nrepl.middleware.session-test/*test-var2* :override}}]
      (with-nrepl-server {}
        (with-nrepl-client
          (is+ :override (repl-value1 (code nrepl.middleware.session-test/*test-var2*))))))))

(defn- alive? [sid]
  (some? (session-util/resolve-session sid)))

(def captured-thread (atom nil))

(deftest sessions-closed-with-connection-test
  (with-every-transport
    (reset! captured-thread nil)
    (let [idle-sess (promise)
          busy-sess (promise)]
      (with-nrepl-server {}
        (with-nrepl-client
          (deliver idle-sess (nrepl/client-session client))
          (deliver busy-sess (nrepl/client-session client))
          (repl-eval @busy-sess
                     (code
                      (do (reset! nrepl.middleware.session-test/captured-thread
                                  (Thread/currentThread))
                          (Thread/sleep 30000))))
          (is (wait-for #(some? @captured-thread)))
          (is+ {:sessions (mc/embeds [(session-id @idle-sess)
                                      (session-id @busy-sess)])}
               (message! client {:op "ls-sessions"})))

        ;; Ran outside of with-nrepl-client, so the connection is closed by now.
        (testing "all sessions cloned by the closed connection are closed"
          (is (wait-for #(not (or (alive? (session-id @idle-sess))
                                  (alive? (session-id @busy-sess)))))))

        (testing "running evaluation is terminated with the session"
          (is (wait-for #(not (.isAlive ^Thread @captured-thread)))))))))

(def-repl-test session-lifecycle
  (is+ {:status #{:error :unknown-session :done}}
       (message! client {:op "whatever" :session "00000000-0000-0000-0000-000000000000"}))
  (let [session-id (nrepl/new-session client)
        session-alive? #(contains? (->> (message! client {:op "ls-sessions"})
                                        :sessions
                                        set)
                                   session-id)]
    (is session-id)
    (is (session-alive?))
    (is+ {:status #{:done :session-closed}}
         (message! client {:op "close" :session session-id}))
    (is (not (session-alive?)))
    (is+ {:status #{:done :error :unknown-session}}
         (message! client {:op "close" :session session-id}))))

(def-repl-test cloned-session-*1-binding
  (is+ 3 (repl-value1 session "(+ 1 2)"))
  (let [new-sess-id (-> (nrepl/message session {:op "clone"})
                        first :new-session)
        cloned-sess (nrepl/client-session client :session new-sess-id)]
    (is+ 3 (repl-value1 cloned-sess "*1"))))

(deftest stop-server-closes-sessions-test
  (with-every-transport
    (let [sid (promise)]
      (with-nrepl-server {}
        (with-nrepl-client
          (deliver sid (session-id (nrepl/client-session client)))
          (is (alive? @sid))))
      (is (wait-for #(not (alive? @sid)))))))

(def-repl-test session-set!
  (repl-eval! session (code
                       (set! *compile-path* "badpath")
                       (set! *warn-on-reflection* true)))
  (is (= ["badpath" true]
         (repl-value1 session (code [*compile-path* *warn-on-reflection*])))))

(def-repl-test exception-in-session-loop-test
  (testing "uncaught exception thrown by the exec task kills the session"
    (let [sess (nrepl/client-session client)]
      (is+ {:status #{:done :uncaught-session-exception :error}}
           (message! sess {:op "eval" :code "(+ 1 2)" :line "bad"}))
      (is (not (alive? (session-id sess)))))))

(def-repl-test interrupt-keeps-session-alive-test
  (let [session' (nrepl/client-session client)
        resp (nrepl/message session' {:op "eval" :code "(Thread/sleep 30000)"})]
    (Thread/sleep 100)
    (is+ {:status #{:done}}
         (message! session' {:op "interrupt"}))
    (is+ {:status #{:done :interrupted}}
         (-> resp nrepl/combine-responses clean-response))
    (is (alive? (session-id session')))
    (is (= 3 (repl-value1 session' (code (+ 1 2)))))))

(def-repl-test dynvars-across-sessions-test
  (repl-eval! session (code (+ 2 3)))
  (is (= 5 (repl-value1 "*1")))

  (let [other-session (nrepl/client-session client)]
    (is (= nil (repl-value1 other-session "*1")))))

;; NREPL-66: ensure that bindings of implementation vars aren't captured by user sessions
;; (https://github.com/clojure-emacs/cider/issues/785)
(def-repl-test ensure-no-*msg*-capture
  (let [r1 (repl-eval! (code (println :foo)))
        r2 (repl-eval! (code (println :foo)))]
    (is (not= (:id r1) (:id r2)))
    (is (= ":foo\n" (:out r1) (:out r2)))))

(def-repl-test streaming-out-buffer-size-1024-bytes
  (testing "new session"
    (is+ [{:out (mc/via count 1024)}
          {:out (mc/via count 1024)}
          {:out (mc/via count 1024)}
          {:out (mc/via count 819)}
          {:value "nil"}
          {:status #{:done}}]
         (->> (repl-eval client (code (print (range 1000))))
              (mapv clean-response))))
  (testing "existing session"
    (is+ [{:out (mc/via count 1024)}
          {:out (mc/via count 1024)}
          {:out (mc/via count 1024)}
          {:out (mc/via count 819)}
          {:value "nil"}
          {:status #{:done}}]
         (->> (repl-eval session (code (print (range 1000))))
              (mapv clean-response)))))

(def-repl-test out-quota-test
  (testing "new session"
    (is+ {:out "(0 1 "}
         (message! client {:op "eval"
                           :out-quota 5
                           :code (code (print (range 1000)))}))
    (is+ {:err "(0 1 "}
         (message! client {:op "eval"
                           :err-quota 5
                           :code (code (binding [*out* *err*]
                                         (print (range 1000))))})))
  (testing "existing session"
    (is+ {:out "(0 1 "}
         (message! session {:op "eval"
                            :out-quota 5
                            :code (code (print (range 1000)))}))
    (is+ {:err "(0 1 "}
         (message! session {:op "eval"
                            :err-quota 5
                            :code (code (binding [*out* *err*]
                                          (print (range 1000))))}))))

(def-repl-test interrupt
  (testing "interrupting a sleep"
    (is+ {:status #{:done :session-idle}}
         (message! session {:op "interrupt"}))
    (is+ {:status #{:done :session-idle}}
         (message! session {:op "interrupt" :interrupt-id "foo"}))

    (let [resp (repl-eval session (code (do
                                          (nrepl.test-helpers/capture-state "thread1" (Thread/currentThread))
                                          (Thread/sleep 10000)
                                          (nrepl.test-helpers/capture-state "no-reach1" true))))]
      (is (wait-for #(get-state "thread1")))
      (is+ {:status #{:done :error :interrupt-id-mismatch}}
           (message! session {:op "interrupt" :interrupt-id "foo"}))
      (is+ {:status #{:done}} (message! session {:op "interrupt"}))
      ;; Interrupted evaluation always receives :interrupted status, but
      ;; sometimes an InterruptedException races in first.
      (is+ (mc/any-of (mc/embeds [{:status #{:done :interrupted}}])
                      (mc/embeds [{:ex "class java.lang.InterruptedException"
                                   :status #{:eval-error}}]))
           (mapv clean-response resp))
      (is (wait-for #(not (.isAlive ^Thread (nrepl.test-helpers/get-state "thread1")))))
      (is+ nil (get-state "no-reach1"))))
  (testing "interruptible code"
    (let [session' (nrepl/client-session client)
          responses (nrepl/message session' {:op :eval
                                             :id "explicit-id"
                                             :code (code (do (nrepl.test-helpers/capture-state "thread2" (Thread/currentThread))
                                                             (loop []
                                                               (if (Thread/interrupted)
                                                                 (throw (RuntimeException. "interrupted"))
                                                                 (recur)))
                                                             (nrepl.test-helpers/capture-state "no-reach2" true)))})]
      (is (wait-for #(nrepl.test-helpers/get-state "thread2")))
      (is+ {:status #{:done}}
           (message! session' {:op "interrupt", :interrupt-id "explicit-id"}))
      (is (wait-for #(not (.isAlive ^Thread (nrepl.test-helpers/get-state "thread2")))))
      (is+ nil (get-state "no-reach2"))
      (is+ (mc/embeds [{:status #{:done :interrupted}}])
           (mapv clean-response responses)))))

(when-not win?
  (def-repl-test non-interruptible-stop-thread
    (testing "non-interruptible code can still be interrupted"
      (with-redefs [nrepl.util.threading/force-stop-delay-ms 500]
        (let [resp (repl-eval session (code
                                       (do (def vol (volatile! 0))
                                           (nrepl.test-helpers/capture-state "thread" (Thread/currentThread))
                                                ;; This never stops on its own.
                                           (while (vswap! vol inc)))))]
          (is (wait-for #(nrepl.test-helpers/get-state "thread")))
          (is+ {:status #{:done}}
               (message! {:op "interrupt"}))
          (is (wait-for #(not (.isAlive ^Thread (nrepl.test-helpers/get-state "thread")))))
          ;; Verify that volatile is not changed anymore.
          (let [v (repl-values session "@vol")]
            (Thread/sleep 500)
            (is (= v (repl-values session "@vol")))))))))

(def-repl-test interrupt-load-file
  (remove-ns 'nrepl.load-file-sample2)
  (let [resp (nrepl/message session {:op "load-file"
                                     :file (slurp "load-file-test/nrepl/load_file_sample2.clj")
                                     :file-path "nrepl/load_file_sample2.clj"
                                     :file-name "load_file_sample2.clj"})]
    (wait-for #(some-> (resolve 'nrepl.load-file-sample2/started) deref))
    (is+ {:status #{:done}} (message! session {:op "interrupt"}))
    (is+ {:status (mc/embeds #{:interrupted})}
         (-> resp
             nrepl/combine-responses
             clean-response))))

;;;; *in*-related

(def-repl-test request-*in*
  (let [responses (repl-eval session "(read)")]
    (is+ {:status #{:need-input}} (first responses))
    (session {:op "stdin" :stdin "(1 2 3)"})
    (is+ {:value "(1 2 3)"} (second responses)))

  (message! session {:op "stdin" :stdin (newline->sys "a\nb\nc\n")})
  (is+ "a" (repl-value1 session "(read-line)"))
  (is+ "b" (repl-value1 session "(read-line)"))
  (is+ "c" (repl-value1 session "(read-line)")))

(def-repl-test request-*in*-eof
  (let [responses (repl-eval session "(read)")]
    (is+ {:status (mc/via #(map keyword %) [:need-input])} (first responses))
    (session {:op "stdin" :stdin []})
    (is+ nil (nrepl/response-values responses))))

(def-repl-test request-*in*-eof-after-input
  ;; EOF that arrives behind buffered input must still be reported, and not be
  ;; swallowed by the read that drains that input.
  (message! session {:op "stdin" :stdin "abc"})
  (message! session {:op "stdin" :stdin []})
  (is+ "abc" (repl-value1 session (code (read-line)))))

(def-repl-test closing-*in*-keeps-the-session-alive
  ;; `slurp` closes the reader it is handed. Doing that to *in* must not take
  ;; the session down: the eval still completes, and later evals on the same
  ;; session are still answered (though *in* itself stays closed).
  (message! session {:op "stdin" :stdin []})
  (is+ "" (repl-value1 session "(slurp *in*)"))
  (is+ 2 (repl-value1 session "(+ 1 1)")))

(def-repl-test rebinding-*in*-keeps-the-session-alive
  ;; *in* may be set! to any Reader, not only a PushbackReader.
  (repl-eval! session (code (set! *in* (java.io.BufferedReader. (java.io.StringReader. "hi\n")))))
  (is+ "hi" (repl-value1 session "(read-line)"))
  (is+ 2 (repl-value1 session "(+ 1 1)")))

(def-repl-test closing-*out*-keeps-the-session-alive
  ;; Flushing a closed *out* after the eval must not take the session down
  ;; either. *out* is rebound for every message, so the next eval gets a
  ;; working once again.
  (repl-eval! session (code (set! *out* (doto (java.io.BufferedWriter. (java.io.StringWriter.)) .close))))
  (is+ 2 (repl-value1 session "(+ 1 1)")))

(def-repl-test request-multiple-read-newline-*in*
  ;; Verify that when ":ohai\n" is supplied (with a newline), a `(read)`
  ;; consumes :ohai but also consumes a newline, so that when we later give "a\n"
  ;; to stdin, and `(read-line)` is issued, it will return "a" and not the
  ;; newline before it.
  (let [responses-seq (repl-eval session "(read)")]
    (is+ {:status #{:need-input}} (first responses-seq))
    (message! {:op "stdin" :stdin (newline->sys ":ohai\n")})
    (is+ {:value ":ohai"} (second responses-seq)))
  (message! {:op "stdin" :stdin (newline->sys "a\n")})
  (is+ "a" (repl-value1 session "(read-line)")))

(def-repl-test request-multiple-read-with-buffered-newline-*in*
  (let [responses (repl-eval session "(read)")]
    (is+ {:status #{:need-input}} (first responses))
    (message! session {:op "stdin" :stdin (newline->sys ":ohai\na\n")})
    (is+ {:value ":ohai"} (second responses)))

  (is+ "a" (repl-value1 session "(read-line)")))

(def-repl-test request-multiple-read-objects-*in*
  (let [responses (repl-eval session "(read)")]
    (is+ {:status #{:need-input}} (first responses))
    (message! session {:op "stdin" :stdin (newline->sys ":ohai :kthxbai\n")})
    (is+ {:value ":ohai"} (second responses)))

  (is+ " :kthxbai" (repl-value1 session "(read-line)")))
