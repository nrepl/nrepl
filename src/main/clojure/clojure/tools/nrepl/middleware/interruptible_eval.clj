
(ns ^{:author "Chas Emerick"}
     clojure.tools.nrepl.middleware.interruptible-eval
  (:require [clojure.tools.nrepl.transport :as t]
            clojure.main)
  (:use [clojure.tools.nrepl.misc :only (response-for returning)])
  (:import clojure.lang.LineNumberingPushbackReader
           (java.io StringReader Writer)
           (java.util.concurrent ArrayBlockingQueue TimeUnit)))

(def ^{:dynamic true
       :doc "The message currently being evaluated."}
      *msg* nil)

(defn evaluate
  "Evaluates some code within the dynamic context defined by a map of `bindings`,
   as per `clojure.core/get-thread-bindings`.

   Uses `clojure.main/repl` to drive the evaluation of :code in a second
   map argument (either a string or a seq of forms to be evaluated), which may
   also optionally specify a :ns (resolved via `find-ns`).  The map MUST
   contain a Transport implementation in :transport; expression results and errors
   will be sent via that Transport.

   Returns the dynamic scope that remains after evaluating all expressions
   in :code.

   It is assumed that `bindings` already contains useful/appropriate entries
   for all vars indicated by `clojure.main/with-bindings`."
  [bindings {:keys [code ns transport] :as msg}]
  (let [bindings (atom (merge bindings (when ns {#'*ns* (-> ns symbol find-ns)})))]
    (try
      (clojure.main/repl
        :init (fn [] (push-thread-bindings @bindings))
        :read (if (string? code)
                (let [reader (LineNumberingPushbackReader. (StringReader. code))]
                  #(read reader false %2))
                (let [q (java.util.concurrent.ArrayBlockingQueue. (count code) false code)]
                  #(or (.poll q 0 java.util.concurrent.TimeUnit/MILLISECONDS) %2)))
        :prompt (fn [])
        :need-prompt (constantly false)
        ; TODO pretty-print?
        :print (fn [v]
                 (reset! bindings (assoc (get-thread-bindings)
                                      #'*3 *2
                                      #'*2 *1
                                      #'*1 v))
                 (t/send transport (response-for msg
                                                 {:value v
                                                  :ns (-> *ns* ns-name str)})))
        ; TODO customizable exception prints
        :caught (fn [e]
                  (let [root-ex (#'clojure.main/root-cause e)]
                    (when-not (instance? ThreadDeath root-ex)
                      (reset! bindings (assoc (get-thread-bindings) #'*e e))
                      (t/send transport (response-for msg {:status :eval-error
                                                           :ex (-> e class str)
                                                           :root-ex (-> root-ex class str)}))
                      (clojure.main/repl-caught e)))))
      @bindings
      (finally
        (pop-thread-bindings)
        (.flush ^Writer (@bindings #'*out*))
        (.flush ^Writer (@bindings #'*err*))))))

#_(defn- pool-size [] (.getPoolSize clojure.lang.Agent/soloExecutor))


(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (case op
      "eval"
      (if-not (:code msg)
        (t/send transport (response-for msg :status #{:error :no-code}))
        (send-off session
          (fn [bindings]
            (alter-meta! session assoc
                         :thread (Thread/currentThread)
                         :eval-msg msg)
            (binding [*msg* msg]
              (returning (dissoc (evaluate bindings msg) #'*msg* #'*agent*)
                         (t/send transport (response-for msg :status :done))
                         (alter-meta! session dissoc :thread :eval-msg))))))
      
      "interrupt"
      ; interrupts are inherently racy; we'll check the agent's :eval-msg's :id and
      ; bail if it's different than the one provided, but it's possible for
      ; that message's eval to finish and another to start before we send
      ; the interrupt / .stop.
      (let [{:keys [id eval-msg ^Thread thread]} (meta session)]
        (if (or (not interrupt-id)
                (= interrupt-id (:id eval-msg)))
          (if-not thread
            (t/send transport (response-for msg :status #{:done :session-idle}))
            (do
              ; notify of the interrupted status before we .stop the thread so
              ; it is received before the standard :done status (thereby ensuring
              ; that is stays within the scope of a clojure.tools.nrepl/message seq
              (t/send transport {:status #{:interrupted}
                                 :id (:id eval-msg)
                                 :session id})
              (.stop thread)
              (t/send transport (response-for msg :status #{:done}))))
          (t/send transport (response-for msg :status #{:error :interrupt-id-mismatch :done}))))
      
      (h msg))))
