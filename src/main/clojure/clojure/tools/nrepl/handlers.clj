(ns clojure.tools.nrepl.handlers
  (:use [clojure.tools.nrepl.misc :only (uuid response-for returning log)])
  (:require (clojure main test)
            [clojure.tools.nrepl.transport :as transport])
  (:import clojure.tools.nrepl.transport.Transport 
           (java.io PipedReader PipedWriter Reader Writer PrintWriter StringReader)
           clojure.lang.LineNumberingPushbackReader))

(defn evaluate
  "Evaluates some code within the dynamic context defined by `bindings`.

   Uses `clojure.main/repl` to drive the evaluation of :code in a second
   map argument, which may also optionally specify a :ns (which is resolved
   via `find-ns`).  The map MUST contain a Transport implementation
   in :transport; expression results and errors will be sent to that
   transport implementation.

   Returns the dynamic scope that resulted after evaluating all expressions
   in :code, as per `clojure.core/get-thread-bindings`.

   It is assumed that `bindings` will contain useful/appropriate entries
   for all vars indicated by `clojure.main/with-bindings`."
  [bindings {:keys [code ns transport] :as msg}]
  (let [code-reader (LineNumberingPushbackReader. (StringReader. code))
        bindings (atom (merge bindings (when ns {#'*ns* (-> ns symbol find-ns)})))]
    (try
      (clojure.main/repl
        :init (fn [] (push-thread-bindings @bindings))
        :read (fn [prompt exit] (read code-reader false exit))
        :prompt (fn [])
        :need-prompt (constantly false)
        ; TODO pretty-print?
        :print (fn [v]
                 (reset! bindings (-> (get-thread-bindings)
                                    (assoc #'*3 *2
                                           #'*2 *1
                                           #'*1 v)
                                    (dissoc #'*agent*)))
                 (transport/send transport (response-for msg
                                             {:value v
                                              :ns (-> *ns* ns-name str)})))
        ; TODO customizable exception prints
        :caught (fn [e]
                  (when-not (instance? ThreadDeath (#'clojure.main/root-cause e))
                    (reset! bindings (-> (get-thread-bindings)
                                       (assoc #'*e e)
                                       (dissoc #'*agent*)))
                    (transport/send transport (response-for msg {:status "error"}))
                    (clojure.main/repl-caught e))))
      @bindings
      (finally
        (pop-thread-bindings)
        (.flush ^Writer (@bindings #'*out*))
        (.flush ^Writer (@bindings #'*err*))))))

#_(defn- pool-size [] (.getPoolSize clojure.lang.Agent/soloExecutor))

(defn unknown-op
  "A handler that always sends an {:unknown-op :status :op op} response."
  [{:keys [op transport] :as msg}]
  (transport/send transport (response-for msg {:status #{:error :unknown-op} :op op})))

(def ^{:private true
       :dynamic true
       :doc "The message currently being evaluated."}
      *msg* nil)

(defn- session-out
  [channel-type session-id transport]
  (let [sb (StringBuilder.)]
    (PrintWriter. (proxy [Writer] []
                    (close [] (.flush ^Writer this))
                    (write [& [x off len]]
                      (locking sb
                        (cond
                          (number? x) (.append sb (char x))
                          (not off) #(.append sb x)
                          (instance? CharSequence x) (.append sb ^CharSequence x (int off) (int len))
                          :else (.append sb ^chars x (int off) (int len)))))
                    (flush []
                      (let [text (locking sb (let [text (str sb)]
                                               (.setLength sb 0)
                                               text))]
                        (when (pos? (count text))
                          (transport/send transport
                            (response-for *msg* {:session session-id
                                                 channel-type text})))))))))

(defn- session-in
  [session-id transport]
  (let [request-input (fn [^PipedReader r]
                        (when-not (.ready r)
                          (transport/send transport
                            (response-for *msg* {:session session-id
                                                 :status :need-input}))))
        writer (PipedWriter.)
        reader (LineNumberingPushbackReader.
                 (proxy [PipedReader] [writer]
                   (close [])
                   (read
                     ([] (request-input this)
                         (let [^Reader this this] (proxy-super read)))
                     ([x] (request-input this)
                          (let [^Reader this this]
                            (if (instance? java.nio.CharBuffer x)
                              (proxy-super read ^java.nio.CharBuffer x)
                              (proxy-super read ^chars x))))
                     ([buf off len]
                       (let [^Reader this this]
                         (request-input this)
                         (proxy-super read buf off len))))))]
    [reader writer]))

(def ^:private sessions (atom {}))

(defn- session-error-handler
  [session-agent ex]
  #_(when-not (or (instance? InterruptedException ex)
                (instance? ThreadDeath ex))
    )
  (log ex "Session error, id " (-> session-agent meta :id)))

(defn- create-session
  ([transport] (create-session transport {}))
  ([transport baseline-bindings]
    (clojure.main/with-bindings
      (let [id (uuid)
            out (session-out :out id transport)
            [in in-writer] (session-in id transport)]
        (binding [*out* out
                  *err* (session-out :err id transport)
                  *in* in
                  *ns* (create-ns 'user)
                  ; clojure.test captures *out* at load-time, so we need to make sure
                  ; runtime output of test status/results is redirected properly
                  ; TODO is this something we need to consider in general, or is this
                  ; specific hack reasonable?
                  clojure.test/*test-out* out]
          (agent (merge baseline-bindings (get-thread-bindings))
            :meta {:id id
                   :stdin-writer in-writer}
            :error-mode :continue
            :error-handler #'session-error-handler))))))

(defn- register-session
  [{:keys [session transport] :as msg}]
  (let [session (create-session transport @session)
        id (-> session meta :id)]
    (swap! sessions assoc id session)
    (transport/send transport (response-for msg {:status :done :new-session id}))))

(defn- close-session
  [{:keys [session transport] :as msg}]
  (swap! sessions dissoc (-> session meta :id))
  (transport/send transport (response-for msg {:status #{:done :session-closed}})))

(defn session
  [h]
  (fn [{:keys [op session transport] :as msg}]
    (let [the-session (if session
                        (@sessions session)
                        (create-session transport))]
      (if-not the-session
        (transport/send transport (response-for msg {:status #{:error :unknown-session}}))
        (let [msg (assoc msg :session the-session)]
          (case op
            "clone" (register-session msg)
            "close" (close-session msg)
            "ls-sessions" (transport/send transport (response-for msg {:status :done
                                                                       :sessions (keys @sessions)})) 
            (h msg)))))))

(defn interruptable-eval
  [h]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (case op
      "eval"
      (if-not (string? (:code msg))
        (transport/send transport (response-for msg {:status #{:error :no-code}}))
        (send-off session
          (fn [bindings]
            (alter-meta! session assoc
                         :thread (Thread/currentThread)
                         :msg-id id)
            (binding [*msg* msg]
              (returning (dissoc (evaluate bindings msg) #'*msg*)
                         (transport/send transport (response-for msg {:status :done})))))))
      
      "interrupt"
      ; interrupts are inherently racy; we'll check the agent's :msg-id and
      ; bail if it's different than the one provided, but it's possible for
      ; that message's eval to finish and another to start before we send
      ; the interrupt / .stop.
      (if (or (not interrupt-id)
              (= interrupt-id (-> session meta :msg-id)))
        (do
          (-> session meta ^Thread (:thread) .stop)
          (transport/send transport {:status #{:interrupted}
                                     :id (-> session meta :msg-id)
                                     :session (-> session meta :id)})
          (transport/send transport (response-for msg {:status #{:done}})))
        (transport/send transport (response-for msg {:status #{:error :interrupt-id-mismatch :done}})))
      
      (h msg))))

(defn add-stdin
  "Writes content to a session-local Writer instance held in its metadata,
   to be picked up by the reader returned by transport-in."
  [h]
  (fn [{:keys [op stdin session transport] :as msg}]
    (if (= op "stdin")
      (do
        (-> session meta ^Writer (:stdin-writer) (.write stdin))
        (transport/send transport (response-for msg {:status :done})))
      (h msg))))

(defn output-subscriptions
  [h]
  (fn [{:keys [op sub unsub] :as msg}]
    (case op
      "sub" ;; TODO
      "unsub"
      (h msg))))

(defn prn-values
  [h]
  (fn [{:keys [op ^Transport transport] :as msg}]
    (if (not= op "eval")
      (h msg)
      (h (assoc msg :transport (reify Transport
                                 (recv [this] (.recv transport))
                                 (recv [this timeout] (.recv transport timeout))
                                 (send [this resp]
                                   (.send transport
                                     (if-let [[_ v] (find resp :value)]
                                       (assoc resp :value (with-out-str (pr v)))
                                       resp)))))))))

(defn default-handler
  []
  (-> unknown-op
    interruptable-eval
    prn-values
    add-stdin
    ; output-subscriptions TODO
    session))
