
(ns ^{:author "Chas Emerick"}
     clojure.tools.nrepl.middleware.pr-values
  (:require [clojure.tools.nrepl.transport :as t])
  (:use [clojure.tools.nrepl.middleware :only (set-descriptor!)])
  (:import clojure.tools.nrepl.transport.Transport))

(defn pr-values
  "Middleware that returns a handler which transforms any :value slots
   in messages sent via the request's Transport to strings via `pr`,
   delegating all actual message handling to the provided handler.

   Requires that results of eval operations are sent in messages in a
   :value slot."
  [h]
  (fn [{:keys [op ^Transport transport] :as msg}]
    (h (assoc msg :transport (reify Transport
                               (recv [this] (.recv transport))
                               (recv [this timeout] (.recv transport timeout))
                               (send [this resp]
                                 (.send transport
                                   (if-let [[_ v] (find resp :value)]
                                     (let [repr (java.io.StringWriter.)]
                                       (assoc resp :value (do (if *print-dup*
                                                                (print-dup v repr)
                                                                (print-method v repr))
                                                              (str repr))))
                                     resp))
                                 this))))))

(set-descriptor! #'pr-values
  {:requires #{}
   :expects #{}
   :handles {}})
