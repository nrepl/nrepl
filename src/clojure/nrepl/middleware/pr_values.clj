(ns nrepl.middleware.pr-values
  {:author "Chas Emerick"}
  (:require
   [clojure.string :as str]
   [nrepl.middleware :as middleware])
  (:import
   nrepl.transport.Transport))


(defn- default-renderer
  "Uses print-dup or print-method to render a value to a string."
  [v]
  (let [printer (if *print-dup* print-dup print-method)
        writer (java.io.StringWriter.)]
    (printer v writer)
    (str writer)))


(defn- rendering-transport
  "Wraps a `Transport` with code which renders the value of messages sent to
  it using the provided function."
  [^Transport transport render-fn]
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
            (assoc resp :value (str/trim-newline (render-fn v)))
            resp)))
      this)))


(defn pr-values
  "Middleware that returns a handler which transforms any `:value` slots in
  messages sent via the request's `Transport` to strings via the provided
  `:renderer` function, delegating all actual message handling to the provided
  handler.

  If no custom renderer is set, this falls back to using `print-dup` or
  `print-method`.

  Requires that results of eval operations are sent in messages in a
  `:value` slot.

  If `:value` is already a string, and a sent message's `:printed-value` slot
  contains any truthy value, then `:value` will not be re-printed.  This allows
  evaluation contexts to produce printed results in `:value` if they so choose,
  and opt out of the printing here."
  [handler]
  (fn [{:keys [op ^Transport transport renderer] :as msg}]
    (let [render-fn (if renderer
                      (find-var (symbol renderer))
                      default-renderer)
          transport (rendering-transport transport render-fn)]
      (handler (assoc msg :transport transport)))))


(middleware/set-descriptor!
  #'pr-values
  {:requires #{}
   :expects #{}
   :handles {}})
