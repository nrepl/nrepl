(ns nrepl.middleware.interruptible-eval
  "Supports the ability to evaluation code. The name of the middleware is
  slightly misleading, as interrupt is currently supported at a session level
  but the name is retained for backwards compatibility."
  {:author "Chas Emerick"}
  (:require
   clojure.main
   clojure.test
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :as caught]
   [nrepl.middleware.print :as print]
   [nrepl.misc :as misc :refer [response-for]]
   [nrepl.transport :as t])
  (:import
   (clojure.lang Compiler$CompilerException
                 LineNumberingPushbackReader LispReader$ReaderException)
   (java.io StringReader Writer)
   (java.lang.reflect Field)))

(def ^:dynamic *msg*
  "The message currently being evaluated."
  nil)

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

(defn- interrupted?
  "Returns true if the given throwable was ultimately caused by an interrupt."
  [^Throwable e]
  (or (instance? ThreadDeath (clojure.main/root-cause e))
      (and (instance? Compiler$CompilerException e)
           (instance? ThreadDeath (.getCause e)))))

(defn evaluator
  "Return a closure that evaluates msg's `:code` (either a string or a seq of
  forms to be evaluated) within the dynamic context of its session. `:ns` can be
  optionally specified (resolved via `find-ns`). The message MUST contain a
  Transport implementation in :transport; expression results and errors will be
  sent via that Transport."
  [msg]
  (fn run []
    (let [{:keys [transport eval ns code line column]} msg
          explicit-ns (some-> ns symbol find-ns)]
      (if (and (some? ns) (nil? explicit-ns))
        (t/send transport (response-for msg {:status #{:error :namespace-not-found :done}
                                             :ns ns}))
        (let [eof (Object.)
              read (if (string? code)
                     (let [reader (source-logging-pushback-reader code line column)
                           read-cond (or (-> msg :read-cond keyword)
                                         :allow)]
                       #(read {:read-cond read-cond :eof eof} reader))
                     (let [code (.iterator ^Iterable code)]
                       #(if (.hasNext code)
                          (.next code)
                          eof)))
              eval-fn (when eval (find-var (symbol eval)))
              ;; eval-fn (if eval (find-var (symbol eval)) clojure.core/eval)
              caught (fn [^Throwable e]
                       (set! *e e)
                       (when-not (interrupted? e)
                         (let [resp {::caught/throwable e
                                     :status :eval-error
                                     :ex (str (class e))
                                     :root-ex (str (class (clojure.main/root-cause e)))}]
                           (t/send transport (response-for msg resp)))))]
          (push-thread-bindings (if explicit-ns
                                  {#'*ns* explicit-ns}
                                  {}))
          (try
            (loop []
              (let [input (try
                            (clojure.main/with-read-known (read))
                            (catch Throwable e
                              (let [e (if (instance? LispReader$ReaderException e)
                                        (ex-info nil {:clojure.error/phase :read-source} e)
                                        e)]
                                ;; If error happens during read phase, call
                                ;; caught-hook but don't continue executing.
                                (caught e)
                                eof)))]
                (when-not (identical? input eof)
                  (try
                    (let [value (if eval-fn
                                  (eval-fn input)
                                  ;; If eval-fn is not provided, call
                                  ;; Compiler/eval directly for slimmer stack.
                                  (Compiler/eval input true))]
                      (set! *3 *2)
                      (set! *2 *1)
                      (set! *1 value)
                      (try
                        ;; *out* has :tag metadata; *err* does not
                        (.flush ^Writer *err*)
                        (.flush *out*)
                        (t/send transport (response-for msg {:ns (str (ns-name *ns*))
                                                             :value value
                                                             ::print/keys #{:value}}))
                        (catch Throwable e
                          (throw (ex-info nil {:clojure.error/phase :print-eval-result} e)))))
                    (catch Throwable e
                      (caught e)))
                  ;; Otherwise, when errors happen during eval/print phase,
                  ;; report the exception but continue executing the
                  ;; remaining readable forms.
                  (recur))))

            (catch Throwable e
              (caught e))
            (finally
              (flush)
              (pop-thread-bindings))))))))

(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h & _configuration]
  (fn [{:keys [op session id transport] :as msg}]
    (let [{:keys [exec]} (meta session)]
      (case op
        "eval"
        (if-not (:code msg)
          (t/send transport (response-for msg :status #{:error :no-code :done}))
          (exec id
                (evaluator msg)
                #(t/send transport (response-for msg :status :done))
                msg))
        (h msg)))))

(set-descriptor! #'interruptible-eval
                 {:requires #{"clone" "close" #'caught/wrap-caught #'print/wrap-print}
                  :expects #{}
                  :handles {"eval"
                            {:doc "Evaluates code. Note that unlike regular stream-based Clojure REPLs, nREPL's `\"eval\"` short-circuits on first read error and will not try to read and execute the remaining code in the message."
                             :requires {"code" "The code to be evaluated."
                                        "session" "The ID of the session within which to evaluate the code."}
                             :optional (merge caught/wrap-caught-optional-arguments
                                              print/wrap-print-optional-arguments
                                              {"id" "An opaque message ID that will be included in responses related to the evaluation, and which may be used to restrict the scope of a later \"interrupt\" operation."
                                               "eval" "A fully-qualified symbol naming a var whose function value will be used to evaluate [code], instead of `clojure.core/eval` (the default)."
                                               "ns" "The namespace in which to perform the evaluation. The supplied namespace must exist already (e.g. be loaded). If no namespace is specified the evaluation falls back to `*ns*` for the session in question."
                                               "file" "The path to the file containing [code]. `clojure.core/*file*` will be bound to this."
                                               "line" "The line number in [file] at which [code] starts."
                                               "column" "The column number in [file] at which [code] starts."
                                               "read-cond" "The options passed to the reader before the evaluation. Useful when middleware in a higher layer wants to process reader conditionals."})
                             :returns {"ns" "*ns*, after successful evaluation of `code`."
                                       "value" "The result of evaluating `code`, often `read`able. This printing is provided by the `print` middleware. Superseded by `ex` and `root-ex` if an exception occurs during evaluation."
                                       "ex" "The type of exception thrown, if any. If present, then `:value` will be absent."
                                       "root-ex" "The type of the root exception thrown, if any. If present, then `:value` will be absent."}}}})
