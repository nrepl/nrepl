(ns nrepl.middleware.pr-values
  {:author "Chas Emerick"}
  (:require
   [clojure.string :as str]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc])
  (:import
   nrepl.transport.Transport))

(defn- default-printer
  "Uses `print-dup` or `print-method` to print a value to a string."
  ([v] (default-printer v nil))
  ([v _opts]
   (let [printer (if *print-dup* print-dup print-method)
         writer (java.io.StringWriter.)]
     (printer v writer)
     (str writer))))

(defn- resolve-printer
  "Resolve a namespaced symbol to a printer var. Returns the var or nil if
  the argument is nil or not resolvable."
  [var-sym]
  (when-let [var-sym (and var-sym (symbol var-sym))]
    (try
      (require (symbol (namespace var-sym)))
      (resolve var-sym)
      (catch Exception ex
        (misc/log ex "Couldn't resolve printer function" var-sym)
        nil))))

(defn- printing-transport
  "Wraps a `Transport` with code which prints the value of messages sent to
  it using the provided function."
  [^Transport transport print-fn opts]
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
                 (let [printed-value (str/trim-newline
                                      ;; Support both functions that take and don't take
                                      ;; an options map as their second param
                                      (if (not-empty opts)
                                        (print-fn v opts)
                                        (print-fn v)))]
                   (assoc resp :value printed-value))
                 resp)))
      this)))

(defn pr-values
  "Middleware that returns a handler which transforms any `:value` slots in
  messages sent via the request's `Transport` to strings via the provided
  `:printer` function, delegating all actual message handling to the provided
  handler.

  If no custom printer is set, this falls back to using `print-dup` or
  `print-method`. The function will be called with the value and any resolved
  `:print-options` from the message.

  Requires that results of eval operations are sent in messages in a
  `:value` slot.

  If `:value` is already a string, and a sent message's `:printed-value` slot
  contains any truthy value, then `:value` will not be re-printed.  This allows
  evaluation contexts to produce printed results in `:value` if they so choose,
  and opt out of the printing here."
  [handler]
  (fn [{:keys [^Transport transport printer print-options] :as msg}]
    (let [print-fn (or (resolve-printer printer) default-printer)
          transport (printing-transport transport print-fn print-options)]
      (handler (assoc msg :transport transport)))))

(set-descriptor! #'pr-values
                 {:requires #{}
                  :expects #{}
                  :handles {}})
