(ns nrepl.middleware.truncate-values
  (:require
   [clojure.tools.logging :as log]
   [nrepl.middleware :refer [set-descriptor!]])
  (:import
   nrepl.transport.Transport))

(defn- default-strategy
  "Uses `:truncate-value` to truncate `v`."
  [v {:keys [truncate-value]}]
  (if (or (nil? truncate-value) (<= (count v) truncate-value))
    nil
    (subs v 0 truncate-value)))

(defn- resolve-strategy
  "Resolve a namespaced symbol to a truncate strategy var. Returns the var or
  nil if the argument is nil or not resolvable."
  [var-sym]
  (when-let [var-sym (and var-sym (symbol var-sym))]
    (try
      (require (symbol (namespace var-sym)))
      (resolve var-sym)
      (catch Exception ex
        (log/warn "Couldn't resolve truncate value strategy function" var-sym)
        nil))))

(defn- truncate
  "Truncates `:value` from resp to a specified size using `:truncate-fn`
  strategy, creating `:truncate-value` and `:truncated` set to \"true\" if
  the message contains the truncated slot."
  [resp truncate-fn opts]
  (if-some [truncated-value (truncate-fn (:value resp) opts)]
    (-> (assoc resp
               :truncated-value truncated-value
               :truncated "true"))
    resp))

(defn- truncate-transport
  "Wraps a `Transport` with code which truncates the value of
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

(defn truncate-values
  "Middleware that returns a handler which truncates any `:value` slot in
  a message to a specified size using the `:truncate-options` slot which
  will be called as args by `:truncate-value-strategy` or the
  `default-strategy`."
  [handler]
  (fn [{:keys [^Transport transport truncate-value-strategy truncate-options]
        :as msg}]
    (let [truncate-fn (or (resolve-strategy truncate-value-strategy) default-strategy)
          transport (truncate-transport transport truncate-fn truncate-options)]
      (handler (assoc msg :transport transport)))))

(set-descriptor! #'truncate-values
                 {:requires #{}
                  :expects #{}
                  :handles {}})
