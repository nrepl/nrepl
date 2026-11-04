(ns nrepl.middleware.print-test
  "Tests for the print middleware. This does not depend on, or use the `eval`
   middleware. Instead, it sends the values to be printed directly in the
   `:value` slot, and uses an echo handler to send it back via the printing
   transport.

   These tests are transport agnostic, and do not deal with sessions, session
   IDs, and message IDs"
  (:refer-clojure :exclude [print])
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.matchers :as mc]
            [nrepl.core :as nrepl :refer [code]]
            [nrepl.middleware.print :as print]
            [nrepl.misc :refer [ignore-exceptions]]
            [nrepl.test-helpers :refer :all]
            [nrepl.transport :as t])
  (:import (java.io Writer)))

(defn echo-handler
  [{:keys [transport] :as msg}]
  (t/send transport (select-keys msg (::print/keys msg))))

(defn test-transport
  "This transport just collects messages sent into an atom"
  [queue]
  (t/fn-transport
   nil
   #(swap! queue conj %)))

(defmacro testing-print
  "Macro for print tests. Exposes a function `handle`, which takes a msg.
   the `:value` in the message will be printed, using whatever extra print
   arguments supplied."
  {:style/indent 1}
  [doc & body]
  `(testing ~doc
     (let [~'handle (fn [msg#]
                      (let [print-handler# (print/wrap-print echo-handler)
                            resps#         (atom [])]
                        (print-handler# (assoc msg#
                                               :transport (test-transport resps#)
                                               ::print/keys (or (::print/keys msg#)
                                                                #{:value})))
                        @resps#))]
       ~@body)))

(defn custom-printer
  [value ^Writer writer opts]
  (.write writer (format "<foo %s %s>" value (or (:sub opts) "..."))))

(deftest value-printing
  (testing-print "bad symbol should fall back to default printer"
    (is+ [{:status        #{::print/error}
           ::print/error "Couldn't resolve var my.missing.ns/printer"}
          {:value "42"}]
         (handle {:value        42
                  ::print/keys  #{:value}
                  ::print/print 'my.missing.ns/printer})))
  (testing-print "custom printing function symbol should be used"
    (is+ [{:value "<foo true ...>"}]
         (handle {::print/keys  #{:value}
                  :value        true
                  ::print/print `custom-printer})))
  (testing-print "empty print options are ignored"
    (is+ [{:value "<foo 42 ...>"}]
         (handle {:value          42
                  ::print/print   `custom-printer
                  ::print/keys    #{:value}
                  ::print/options {}})))
  (testing-print "options should be passed to printer"
    (is+ [{:value "<foo 3 bar>"}]
         (handle {:value          3
                  ::print/print   `custom-printer
                  ::print/keys    #{:value}
                  ::print/options {:sub "bar"}}))))

(deftest override-value-printing
  (testing-print "custom ::print/keys"
    (is+ [{:out "[1 2 3 4 5]"}]
         (handle {:value       "value"
                  :out         [1 2 3 4 5]
                  ::print/keys [:out]})))
  (testing-print "empty ::print/keys"
    (is+ [(mc/equals {})]
         (handle {:value       [1 2 3 4 5]
                  ::print/keys []}))))

(deftest streamed-printing
  (testing-print "value response arrives before ns response"
    (is+ [{:value "(0 1 2 3 4 5 6 7 8 9)"} (mc/equals {})]
         (handle {:value          (range 10)
                  ::print/stream? 1})))
  (testing-print "large output should be streamed"
    (is+ [{:value #"^\(0 1 2 3"}
          {:value #"510 511\)$"}
          (mc/equals {})]
         (handle {:value          (range 512)
                  ::print/stream? 1})))
  (testing-print "respects buffer-size option"
    (is+ [{:value "(0 1 2 3"}
          {:value " 4 5 6 7"}
          {:value " 8 9 10 "}
          {:value "11 12 13"}
          {:value " 14 15)"}
          {}]
         (handle {:value              (range 16)
                  ::print/stream?     1
                  ::print/buffer-size 8})))
  (testing-print "works with custom printer"
    (is+ [{:value #"^<foo \(0 1 2 3"}
          {:value #"510 511\) ...>$"}
          (mc/equals {})]
         (handle {:value          (range 512)
                  ::print/stream? 1
                  ::print/print   `custom-printer})))
  (testing-print "works with custom printer and print-options"
    (is+ [{:value #"^<foo \(0 1 2 3"}
          {:value #"510 511\) bar>$"}
          (mc/equals {})]
         (handle {:value          (range 512)
                  ::print/stream? 1
                  ::print/print   `custom-printer
                  ::print/options {:sub "bar"}}))))

(deftest streamed-printing-with-newlines
  (testing-print ""
    (is+ [{:value "abcd\nefgh\n"} {:value "ijkl"} {}]
         (handle {:value        (symbol "abcd\nefgh\nijkl")
                  ::print/stream? 1}))))

(deftest multiple-keys
  (testing-print "respects buffer-size option"
    (is+ [{:value "(0 1 2 3"}
          {:value " 4 5 6)"}
          {:something-else "(6 5 4 3"}
          {:something-else " 2 1 0)"}
          {}]
         (handle {:value              (range 7)
                  :something-else     (reverse (range 7))
                  ::print/stream?     1
                  ::print/buffer-size 8
                  ::print/keys        [:value :something-else]}))))

(deftest print-quota
  (testing-print "quota option respected"
    (is+ [{:value                 "(0 1 2 3"
           :status                #{::print/truncated}
           ::print/truncated-keys [:value]}]
         (handle {:value        (range 512)
                  ::print/quota 8})))
  (testing-print "works with streamed printing"
    (is+ [{:value "(0 1 2 3"}
          {:status #{::print/truncated}}
          (mc/equals {})]
         (handle {:value          (range 512)
                  ::print/stream? 1
                  ::print/quota   8})))

  (testing-print "works with custom printer"
    (is+ [{:value                 "<foo (0 "
           :status                #{::print/truncated}
           ::print/truncated-keys [:value]}]
         (handle {:value        (range 512)
                  ::print/print `custom-printer
                  ::print/quota 8})))
  (testing-print "works with custom printer and streamed printing"
    (is+ [{:value "<foo (0 "}
          {:status #{::print/truncated}}
          (mc/equals {})]
         (handle {:value          (range 512)
                  ::print/print   `custom-printer
                  ::print/stream? 1
                  ::print/quota   8}))))

(defn custom-printer-2
  [value ^Writer writer]
  (.write writer (format "<bar %s>" value)))

;; These tests used to the `session-print-configuration` tests from `core-test`.
;; here, we are simply testing the use of dynamic vars to configure printing
;; behaviour, thus are not going via the `session` middleware to do that.

(deftest dynamic-var-print-configuration
  (testing-print "setting *print-fn* works"
    (is+ [{:value "<bar (0 1 2 3 4 5 6 7 8 9)>"}]
         (binding [print/*print-fn* custom-printer-2]
           (handle {:value (range 10)}))))
  (testing-print "request can still override *print-fn*"
    (is+ [{:value "<foo (0 1 2 3 4 5 6 7 8 9) ...>"}]
         (binding [print/*print-fn* custom-printer-2]
           (handle {:value        (range 10)
                    ::print/print `custom-printer}))))
  (testing-print "setting stream options works"
    (is+ [{:value "<bar (0 "}
          {:value "1 2 3 4 "}
          {:value "5 6 7 8 "}
          {:value "9)>"}
          {}]
         (binding [print/*print-fn*    custom-printer-2
                   print/*stream?*     true
                   print/*buffer-size* 8]
           (handle {:value (range 10)}))))
  (testing-print "request can still override stream options"
    (is+ [{:value "<bar (0 1 2 3 4 5 6 7 8 9)>"}]
         (binding [print/*print-fn*    custom-printer-2
                   print/*stream?*     true
                   print/*buffer-size* 8]
           (handle {:value          (range 10)
                    ::print/stream? nil})))

    (is+ [{:value "<bar (0 1 2 3 4 "}
          {:value "5 6 7 8 9)>"}
          {}]
         (binding [print/*print-fn*    custom-printer-2
                   print/*stream?*     true
                   print/*buffer-size* 8]
           (handle {:value              (range 10)
                    ::print/buffer-size 16}))))
  (testing-print "setting *quota* works"
    (is+ [{:value                 "<bar (0 "
           :status                #{:nrepl.middleware.print/truncated}
           ::print/truncated-keys [:value]}]
         (binding [print/*print-fn* custom-printer-2
                   print/*quota*    8]
           (handle {:value (range 512)}))))
  (testing-print "request can still override *quota*"
    (is+ [{:value                 "<bar (0 1 2 3 4 "
           :status                #{:nrepl.middleware.print/truncated}
           ::print/truncated-keys [:value]}]
         (binding [print/*print-fn* custom-printer-2
                   print/*quota*    8]
           (handle {:value        (range 512)
                    ::print/quota 16})))))

(deftest unicode-printing-test
  (testing-print "unicode characters are handled correctly"
    (let [big-naughty-string (slurp (io/resource "blns.txt"))]
      (is+ [{:value (pr-str big-naughty-string)}
            (mc/equals {})]
           (handle {:value              big-naughty-string
                    ;; Enable streaming printer to force the usage
                    ;; of replying-PrintWriter.
                    ::print/stream?     1
                    ::print/buffer-size 100000})))))

(def-repl-test streamed-printing-2
  (testing "multiple forms"
    (is+ [{:value "(0 1 2 3 4 5 6 7 8 9)"}
          {:ns "user"}
          {:value "(0 1 2 3 4 5 6 7 8 9)"}
          {:ns "user"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (range 10)
                               (range 10))
                   ::print/stream? 1})))

  (testing "*out* still handled correctly"
    (is+ [{:out "0\n"}
          {:out "1\n"}
          {:value "(nil nil)"}
          {:ns "user"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (->> (range 2)
                                    (map println)))
                   ::print/stream? 1}))))

;; TODO: this test is commented because the JVMTI StopThread doesn't reliably
;; kill the endlessly printing thread for some reason. This should be
;; investigated in the future.
#_(def-repl-test streamed-printing-interrupt
  ;; Making the buffer size large expose the odd of interrupting half way through
  ;; a message being written, which was the cause of https://github.com/nrepl/nrepl/issues/132
    (with-redefs [nrepl.util.threading/force-stop-delay-ms 500]
      (let [eval-responses
            (message session {:op "eval"
                              :code (code
                                     (do (nrepl.test-helpers/capture-state "thread" (Thread/currentThread))
                                         (range 1000000)))
                              ::print/stream? 1
                              ::print/buffer-size 100000})
            ^Thread eval-thread (wait-for #(get-state "thread"))]
        (when (is eval-thread)
        ;; Don't check the result of the next message as it sometimes dies because
        ;; of session close.
          (ignore-exceptions (message! session {:op "interrupt"}))
          (is (wait-for #(not (.isAlive eval-thread)) 5000))
          (is+ {:value #"^\(0 1 2 3"} (first eval-responses))))))
