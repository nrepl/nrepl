(ns nrepl.middleware.truncate-outputs
  (:require
   [clojure.tools.logging :as log]
   [nrepl.middleware :refer [set-descriptor!]])
  (:import
   nrepl.transport.Transport))

(defn- default-strategy
  "Uses `:truncate-output` to truncate `v`."
  [v {:keys [truncate-output]}]
  (if (or (nil? truncate-output) (<= (count v) truncate-output))
    nil
    (subs v 0 truncate-output)))

(defn- resolve-strategy
  "Resolve a namespaced symbol to a truncate strategy var. Returns the var or
  nil if the argument is nil or not resolvable."
  [var-sym]
  (when-let [var-sym (and var-sym (symbol var-sym))]
    (try
      (require (symbol (namespace var-sym)))
      (resolve var-sym)
      (catch Exception ex
        (log/warn "Couldn't resolve truncate output strategy function" var-sym)
        nil))))

(defn- truncate
  "Truncates `:out` and `:err` from resp to a specified size using
  `:truncate-fn` strategy, creating `:truncate-out` and `:truncate-err`
  with `:truncated` set to \"true\" if the message contains the truncated
  slot."
  [resp truncate-fn opts]
  (merge
   (if-some [truncated-out (truncate-fn (:out resp) opts)]
     (-> (assoc resp
                :truncated-out truncated-out
                :truncated "true"))
     resp)
   (if-some [truncated-err (truncate-fn (:err resp) opts)]
     (-> (assoc resp
                :truncated-err truncated-err
                :truncated "true"))
     resp)))

(defn- truncate-transport
  "Wraps a `Transport` with code which truncates the outputs of
  messages sent to it."
  [^Transport transport truncate-fn opts]
  (reify Transport
    (recv [this]
      (.recv transport))
    (recv [this timeout]
      (.recv transport timeout))
    (send [this resp]
      (.send transport (truncate resp truncate-fn opts))
      this)))

(defn truncate-outputs
  "Middleware that returns a handler which truncates any `:out` or
  `:err` slots in a message to a specified size using the `:truncate-options`
  slot which will be called as args by `:truncate-output-strategy` or the
  `default-strategy`."
  [handler]
  (fn [{:keys [^Transport transport truncate-output-strategy truncate-options]
        :as msg}]
    (let [truncate-fn (or (resolve-strategy truncate-output-strategy) default-strategy)
          transport (truncate-transport transport truncate-fn truncate-options)]
      (handler (assoc msg :transport transport)))))

(set-descriptor! #'truncate-outputs
                 {:requires #{}
                  :expects #{}
                  :handles {}})
