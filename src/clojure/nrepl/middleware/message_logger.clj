(ns nrepl.middleware.message-logger
  {:added "0.6.0"}
  (:require [clojure.string :as str]
            [nrepl.middleware :refer [set-descriptor!]]
            nrepl.middleware.session
            [nrepl.logging :as log])
  (:import
   nrepl.transport.Transport))

(defn- default-logger
  [channel msg]
  (log/info channel msg))

(defn- logger-transport
  "Wraps a `Transport` with code which logs the value of messages sent to
  it using the provided function."
  [^Transport transport logger-fn]
  (reify Transport
    (recv [this]
      (.recv transport))
    (recv [this timeout]
      (.recv transport timeout))
    (send [this resp]
      (logger-fn :decoded-out resp)
      (.send transport resp)
      this)))

(defn logger
  "Middleware that returns a handler which logs messages sent via the
  request's `Transport` with the `:logger` function from server-opts.

  Adds a new transport to the msg if `verbose` is true, it's
  supposed to be used in non-production environments, you can check
  the decoded messages (the request maps) with the logger function."
  [handler]
  (fn [{:keys [^Transport transport server-opts] :as msg}]
    (if (:verbose server-opts)
      (let [logger-fn (or (deref (:logger server-opts))
                          (if (= :silenced (:default-logger-behaviour server-opts))
                            (constantly nil)
                            default-logger))
            transport (logger-transport transport logger-fn)]
        (logger-fn :decoded-in (dissoc msg :transport :server-opts))
        (handler (assoc msg :transport transport)))
      (handler msg))))

(set-descriptor! #'logger
                 {:requires #{}
                  :expects #{#'nrepl.middleware.session/session}
                  :handles {}})
