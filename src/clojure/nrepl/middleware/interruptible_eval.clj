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
   [java.util.concurrent BlockingQueue Executor SynchronousQueue ThreadFactory ThreadPoolExecutor TimeUnit]
   java.util.concurrent.atomic.AtomicLong))

(def ^:dynamic *msg*
  "The message currently being evaluated."
  nil)

(def ^:dynamic *eval*
  "Function returning the evaluation of its argument."
  nil)

(defmacro ^{:private true} def-dynamic-when-not-resolved
  "Define a dynamic var if it can't be resolved."
  [sym value]
  (when-not (resolve sym)
    `(def ~(vary-meta sym assoc :dynamic true) ~value)))

;; Needed for backward compatibility with Clojure 1.7 and 1.8.
;; See https://github.com/nrepl/nrepl/issues/33 for details.
(def-dynamic-when-not-resolved *print-namespace-maps* nil)

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
  [bindings {:keys [code ns transport session eval file line column] :as msg}]
  (let [explicit-ns-binding (when-let [ns (and ns (-> ns symbol find-ns))]
                              {#'*ns* ns})
        original-ns (bindings #'*ns*)
        maybe-restore-original-ns (fn [bindings]
                                    (if-not explicit-ns-binding
                                      bindings
                                      (assoc bindings #'*ns* original-ns)))
        file (or file (get bindings #'*file*))
        bindings (atom (merge bindings explicit-ns-binding {#'*file* file}))
        session (or session (atom nil))
        out (@bindings #'*out*)
        err (@bindings #'*err*)]
    (if (and ns (not explicit-ns-binding))
      (t/send transport (response-for msg {:status #{:error :namespace-not-found :done}
                                           :ns ns}))
      (with-bindings @bindings
        (try
          (clojure.main/repl
           :eval (if eval (find-var (symbol eval)) clojure.core/eval)
           ;; clojure.main/repl paves over certain vars even if they're already thread-bound
           :init #(do (set! *compile-path* (@bindings #'*compile-path*))
                      (set! *1 (@bindings #'*1))
                      (set! *2 (@bindings #'*2))
                      (set! *3 (@bindings #'*3))
                      (set! *e (@bindings #'*e))
                      (when (resolve '*print-namespace-maps*)
                        (set! *print-namespace-maps* (@bindings #'*print-namespace-maps*)))
                      (alter-var-root #'*out* (constantly (@bindings #'*out*)))
                      (alter-var-root #'*msg* (constantly *msg*)))
           :read (if (string? code)
                   (let [reader (source-logging-pushback-reader code line column)]
                     #(read {:read-cond :allow :eof %2} reader))
                   (let [code (.iterator ^Iterable code)]
                     #(or (and (.hasNext code) (.next code)) %2)))
           :prompt (fn [])
           :need-prompt (constantly false)
           ;; TODO: pretty-print?
           :print (fn [v]
                    (reset! bindings (assoc (capture-thread-bindings)
                                            #'*3 *2
                                            #'*2 *1
                                            #'*1 v))
                    (.flush ^Writer err)
                    (.flush ^Writer out)
                    (reset! session (maybe-restore-original-ns @bindings))
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
                         (reset! bindings (assoc (capture-thread-bindings) #'*e e))
                         (reset! session (maybe-restore-original-ns @bindings))
                         (t/send transport (response-for msg {:status :eval-error
                                                              :ex (-> e class str)
                                                              :root-ex (-> root-ex class str)}))
                         (clojure.main/repl-caught e)))))
          (finally
            (.flush ^Writer out)
            (.flush ^Writer err)))))
    (maybe-restore-original-ns @bindings)))

(defn- configure-thread-factory
  "Returns a new ThreadFactory for the given session.  This implementation
   generates daemon threads, with names that include the session id."
  []
  (let [session-thread-counter (AtomicLong. 0)
        ;; Create a constant dcl for use across evaluations. This allows
        ;; modifications to the classloader to persist.
        cl (clojure.lang.DynamicClassLoader.
            (.getContextClassLoader (Thread/currentThread)))]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable
                       (format "nREPL-worker-%s" (.getAndIncrement session-thread-counter)))
          (.setDaemon true)
          (.setContextClassLoader cl))))))

(defn- configure-executor
  "Returns a ThreadPoolExecutor, configured (by default) to
   have 1 core thread, use an unbounded queue, create only daemon threads,
   and allow unused threads to expire after 30s."
  [& {:keys [keep-alive queue thread-factory]
      :or {keep-alive 30000
           queue (SynchronousQueue.)}}]
  (let [^ThreadFactory thread-factory (or thread-factory (configure-thread-factory))]
    (ThreadPoolExecutor. 1 Integer/MAX_VALUE
                         (long 30000) TimeUnit/MILLISECONDS
                         ^BlockingQueue queue
                         thread-factory)))

(def default-executor (delay (configure-executor)))

;; A little mini-agent implementation. Needed because agents cannot be used to host REPL
;; evaluation: http://dev.clojure.org/jira/browse/NREPL-17
(defn- prep-session
  [session]
  (locking session
    (returning session
               (when-not (-> session meta :queue)
                 (alter-meta! session assoc :queue (atom clojure.lang.PersistentQueue/EMPTY))))))

(declare run-next)
(defn- run-next*
  [session ^Executor executor]
  (let [qa (-> session meta :queue)]
    (loop []
      (let [q @qa
            qn (pop q)]
        (if-not (compare-and-set! qa q qn)
          (recur)
          (when (seq qn)
            (.execute executor (run-next session executor (peek qn)))))))))

(defn- run-next
  [session executor f]
  #(try
     (f)
     (finally
       (run-next* session executor))))

(defn queue-eval
  "Queues the function for the given session."
  [session ^Executor executor f]
  (let [qa (-> session prep-session meta :queue)]
    (loop []
      (let [q @qa]
        (if-not (compare-and-set! qa q (conj q f))
          (recur)
          (when (empty? q)
            (.execute executor (run-next session executor f))))))))

(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h & configuration]
  (let [executor (:executor configuration @default-executor)]
    (fn [{:keys [op session interrupt-id id transport] :as msg}]
      (case op
        "eval"
        (if-not (:code msg)
          (t/send transport (response-for msg :status #{:error :no-code :done}))
          (queue-eval session executor
                      (fn []
                        (alter-meta! session assoc
                                     :thread (Thread/currentThread)
                                     :eval-msg msg)
                        (binding [*msg* msg]
                          (evaluate @session msg)
                          (t/send transport (response-for msg :status :done))
                          (alter-meta! session dissoc :thread :eval-msg)))))

        "interrupt"
        ;; interrupts are inherently racy; we'll check the agent's :eval-msg's :id and
        ;; bail if it's different than the one provided, but it's possible for
        ;; that message's eval to finish and another to start before we send
        ;; the interrupt / .stop.
        (let [{:keys [id eval-msg ^Thread thread]} (meta session)]
          (if (or (not interrupt-id)
                  (= interrupt-id (:id eval-msg)))
            (if-not thread
              (t/send transport (response-for msg :status #{:done :session-idle}))
              (do
                ;; notify of the interrupted status before we .stop the thread so
                ;; it is received before the standard :done status (thereby ensuring
                ;; that is stays within the scope of a nrepl/message seq
                (t/send transport {:status #{:interrupted}
                                   :id (:id eval-msg)
                                   :session id})
                (.stop thread)
                (t/send transport (response-for msg :status #{:done}))))
            (t/send transport (response-for msg :status #{:error :interrupt-id-mismatch :done}))))

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
