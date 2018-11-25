(ns nrepl.middleware.truncate-values-and-outputs
  (:require
   [clojure.tools.logging :as log]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for uuid]]
   [nrepl.transport :as t])
  (:import
   nrepl.transport.Transport))

(defn- default-value-strategy
  "Uses `:truncate-value` to truncate `v`."
  [v {:keys [truncate-value]}]
  (if (or (nil? truncate-value) (<= (count v) truncate-value))
    nil
    (subs v 0 truncate-value)))

(defn- default-output-strategy
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
        (log/warn "Couldn't resolve truncate strategy function" var-sym)
        nil))))

(defonce raw-results (atom {}))

(defn- check-truncate
  [resp k truncate-fn opts]
  (if-some [truncated (truncate-fn (k resp) opts)]
    (let [raw-id (uuid)]
      (swap! raw-results merge {raw-id [k (k resp)]})
      (assoc resp
             k truncated
             :truncated "true"
             :raw-id raw-id))
    resp))

(defn- truncate-msg
  "Truncates `:value`, `:out` and `:err` from resp to a specified size using
   using `:truncate-fn` strategy, creating `:truncate-value` or `truncate-out`
  or `truncate-err` and `:truncated` set to \"true\" if the message contains
  the truncated slot."
  [resp value-truncate-fn output-truncate-fn opts]
  (let [raw-id (uuid)]
    (-> resp
        (check-truncate :value value-truncate-fn opts)
        (check-truncate :out output-truncate-fn opts)
        (check-truncate :err output-truncate-fn opts))))

(defn- truncate-transport
  "Wraps a `Transport` with code which truncates the values and outputs
  of messages sent to it."
  [^Transport transport value-truncate-fn output-truncate-fn opts]
  (reify Transport
    (recv [this]
      (.recv transport))
    (recv [this timeout]
      (.recv transport timeout))
    (send [this resp]
      (.send transport (truncate-msg resp value-truncate-fn output-truncate-fn opts))
      this)))

(defn truncate
  "Middleware that returns a handler which truncates any `:value`, `out` or
  `err` slot in a message to a specified size using the `:truncate-options`
  slot. It also handles fetching of raw values or outputs which were
  truncated for a specific request `ID`."
  [handler]
  (fn [{:keys [^Transport transport op truncate-value-strategy truncate-output-strategy truncate-options]
        :as msg}]
    (if (= op "fetch-raw")
      (if-some [[k result] (get @raw-results (:raw-id msg))]
        (do
          (swap! raw-results dissoc (:raw-id msg))
          (t/send transport (response-for msg k result :status :done)))
        (t/send transport (response-for msg :status #{:error :inexistent-id :done})))
      (let [value-truncate-fn (or (resolve-strategy truncate-value-strategy)
                                  default-value-strategy)
            output-truncate-fn (or (resolve-strategy truncate-output-strategy)
                                   default-output-strategy)
            transport (truncate-transport
                       transport value-truncate-fn output-truncate-fn truncate-options)]
        (handler (assoc msg :transport transport))))))

(set-descriptor! #'truncate
                 {:requires #{}
                  :expects #{}
                  :handles {}})
