(ns nrepl.middleware.interruptible-eval
  {:author "Chas Emerick"}
  (:require
   clojure.main
   [nrepl.middleware :refer [set-descriptor!]]
   nrepl.middleware.pr-values
   [nrepl.misc :refer [response-for returning]]
   [nrepl.transport :as t])
  (:import
   clojure.lang.LineNumberingPushbackReader
   [java.io FilterReader LineNumberReader StringReader Writer]
   java.lang.reflect.Field
   java.util.concurrent.Executor))

(def ^:dynamic *msg*
  "The message currently being evaluated."
  nil)

(def ^:dynamic *eval*
  "Function returning the evaluation of its argument."
  nil)

(defn- capture-thread-bindings
  "Capture thread bindings, excluding nrepl implementation vars."
  []
  (dissoc (get-thread-bindings) #'*msg* #'*eval*))

(defn- set-line!
  [^LineNumberingPushbackReader reader line]
  (-> reader (.setLineNumber line)))

(defn- set-column!
  [^LineNumberingPushbackReader reader column]
  (when-let [field (->> LineNumberingPushbackReader
                        (.getDeclaredFields)
                        (filter #(= "_columnNumber" (.getName ^Field %)))
                        first)]
    (-> ^Field field
        (doto (.setAccessible true))
        (.set reader column))))

(defn- source-logging-pushback-reader
  [code line column]
  (let [reader (LineNumberingPushbackReader. (StringReader. code))]
    (when line (set-line! reader (int line)))
    (when column (set-column! reader (int column)))
    reader))

(defn evaluate
  "Evaluates a msg's code within the dynamic context of its session.

   Uses `clojure.main/repl` to drive the evaluation of :code (either a string
   or a seq of forms to be evaluated), which may also optionally specify a :ns
   (resolved via `find-ns`).  The map MUST contain a Transport implementation
   in :transport; expression results and errors will be sent via that Transport."
  [{:keys [code ns transport session eval file line column] :as msg}]
  (let [explicit-ns (and ns (-> ns symbol find-ns))
        original-ns (@session #'*ns*)
        maybe-restore-original-ns (if explicit-ns
                                    #(assoc % #'*ns* original-ns)
                                    identity)
        session (or session (atom {}))]
    (if (and ns (not explicit-ns))
      (t/send transport (response-for msg {:status #{:error :namespace-not-found :done}
                                           :ns ns}))
      (try
        (clojure.main/repl
         :eval (if eval (find-var (symbol eval)) clojure.core/eval)
         :init #(let [bindings
                      (-> (get-thread-bindings)
                          (into @session)
                          (cond-> explicit-ns (assoc #'*ns* explicit-ns)
                                  file (assoc #'*file* file)))]
                  (pop-thread-bindings)
                  (push-thread-bindings bindings))
         :read (if (string? code)
                 (let [reader (source-logging-pushback-reader code line column)]
                   #(read {:read-cond :allow :eof %2} reader))
                 (let [code (.iterator ^Iterable code)]
                   #(or (and (.hasNext code) (.next code)) %2)))
         :prompt #(reset! session (maybe-restore-original-ns (capture-thread-bindings)))
         :need-prompt (constantly true)
         ;; TODO: pretty-print?
         :print (fn [v]
                  (.flush *err*)
                  (.flush *out*)
                  (t/send transport (response-for msg
                                                  {:value v
                                                   :ns (-> *ns* ns-name str)})))
         ;; TODO: customizable exception prints
         :caught (fn [e]
                   (let [root-ex (#'clojure.main/root-cause e)
                         previous-cause (.getCause e)]
                     ;; Check if the root cause or previous cause of the exception
                     ;; is a ThreadDeath exception. In case the exception is a
                     ;; CompilerException, the root cause is not returned by
                     ;; the root-cause function, so we check the previous cause
                     ;; instead.
                     (when-not (or (instance? ThreadDeath root-ex)
                                   (instance? ThreadDeath previous-cause))
                       (t/send transport (response-for msg {:status :eval-error
                                                            :ex (-> e class str)
                                                            :root-ex (-> root-ex class str)}))
                       (clojure.main/repl-caught e)))))
        (finally
          (some-> ^Writer (@session #'*err*) .flush)
          (some-> ^Writer (@session #'*out*) .flush))))))

(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h & configuration]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (let [{:keys [interrupt exec] session-id :id} (meta session)]
      (case op
        "eval"
        (if-not (:code msg)
          (t/send transport (response-for msg :status #{:error :no-code :done}))
          (exec id
                #(binding [*msg* msg]
                   (evaluate msg))
                #(t/send transport (response-for msg :status :done))))

        "interrupt"
        (let [interrupted-id (when interrupt (interrupt interrupt-id))]
          (case interrupted-id
            nil (t/send transport (response-for msg :status #{:error :interrupt-id-mismatch :done}))
            :idle (t/send transport (response-for msg :status #{:done :session-idle}))
            (do
              ;; interrupt prevents the interrupted computation to be ack'ed,
              ;; so a :done will never ne emitted before :interrupted
              (t/send transport {:status #{:interrupted :done}
                                 :id interrupted-id
                                 :session session-id})
              (t/send transport (response-for msg :status #{:done})))))

        (h msg)))))

(set-descriptor! #'interruptible-eval
                 {:requires #{"clone" "close" #'nrepl.middleware.pr-values/pr-values}
                  :expects #{}
                  :handles {"eval"
                            {:doc "Evaluates code."
                             :requires {"code" "The code to be evaluated."
                                        "session" "The ID of the session within which to evaluate the code."}
                             :optional {"id" "An opaque message ID that will be included in responses related to the evaluation, and which may be used to restrict the scope of a later \"interrupt\" operation."
                                        "eval" "A fully-qualified symbol naming a var whose function value will be used to evaluate [code], instead of `clojure.core/eval` (the default)."
                                        "file" "The path to the file containing [code]. `clojure.core/*file*` will be bound to this."
                                        "line" "The line number in [file] at which [code] starts."
                                        "column" "The column number in [file] at which [code] starts."}
                             :returns {"ns" "*ns*, after successful evaluation of `code`."
                                       "values" "The result of evaluating `code`, often `read`able. This printing is provided by the `pr-values` middleware, and could theoretically be customized. Superseded by `ex` and `root-ex` if an exception occurs during evaluation."
                                       "ex" "The type of exception thrown, if any. If present, then `values` will be absent."
                                       "root-ex" "The type of the root exception thrown, if any. If present, then `values` will be absent."}}
                            "interrupt"
                            {:doc "Attempts to interrupt some code evaluation."
                             :requires {"session" "The ID of the session used to start the evaluation to be interrupted."}
                             :optional {"interrupt-id" "The opaque message ID sent with the original \"eval\" request."}
                             :returns {"status" "'interrupted' if an evaluation was identified and interruption will be attempted
'session-idle' if the session is not currently evaluating any code
'interrupt-id-mismatch' if the session is currently evaluating code sent using a different ID than specified by the \"interrupt-id\" value "}}}})
