(ns nrepl.middleware.pr-values
  {:author "Chas Emerick"}
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [nrepl.middleware :refer [set-descriptor!]])
  (:import
   nrepl.transport.Transport))

(defn- default-renderer
  "Uses print-dup or print-method to render a value to a string."
  [v opts]
  (let [printer (if *print-dup* print-dup print-method)
        writer (java.io.StringWriter.)]
    (printer v writer)
    (str writer)))

(defn- resolve-renderer
  "Resolve a namespaced symbol to a rendering var. Returns the var or nil if
  the argument is nil or not resolvable."
  [var-sym]
  (when-let [var-sym (and var-sym (symbol var-sym))]
    (try
      (require (symbol (namespace var-sym)))
      (resolve var-sym)
      (catch Exception ex
        (log/warn "Couldn't resolve rendering function" var-sym)
        nil))))

(defn- rendering-transport
  "Wraps a `Transport` with code which renders the value of messages sent to
  it using the provided function."
  [^Transport transport render-fn opts]
  (reify Transport
    (recv [this]
      (.recv transport))
    (recv [this timeout]
      (.recv transport timeout))
    (send [this resp]
      (.send transport
             (if (and (string? (:value resp)) (:printed-value resp))
               (dissoc resp :printed-value)
               (if-let [[_ v] (find resp :value)]
                 (assoc resp :value (str/trim-newline (render-fn v opts)))
                 resp)))
      this)))

(defn pr-values
  "Middleware that returns a handler which transforms any `:value` slots in
  messages sent via the request's `Transport` to strings via the provided
  `:renderer` function, delegating all actual message handling to the provided
  handler.

  If no custom renderer is set, this falls back to using `print-dup` or
  `print-method`. The function will be called with the value and any resolved
  `:render-options` from the message.

  Requires that results of eval operations are sent in messages in a
  `:value` slot.

  If `:value` is already a string, and a sent message's `:printed-value` slot
  contains any truthy value, then `:value` will not be re-printed.  This allows
  evaluation contexts to produce printed results in `:value` if they so choose,
  and opt out of the printing here."
  [handler]
  (fn [{:keys [op ^Transport transport renderer render-options] :as msg}]
    (let [render-fn (or (resolve-renderer renderer) default-renderer)
          transport (rendering-transport transport render-fn render-options)]
      (handler (assoc msg :transport transport)))))

(set-descriptor! #'pr-values
                 {:requires #{}
                  :expects #{}
                  :handles {}})
