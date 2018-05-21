(ns nrepl.middleware.pr-values
  {:author "Chas Emerick"}
  (:require [nrepl.middleware :refer [set-descriptor!]])
  (:import nrepl.transport.Transport))

(defn pr-values
  "Middleware that returns a handler which transforms any :value slots
   in messages sent via the request's Transport to strings via `pr`,
   delegating all actual message handling to the provided handler.

   Requires that results of eval operations are sent in messages in a
   :value slot.

   If :value is already a string, and a sent message's :printed-value
   slot contains any truthy value, then :value will not be re-printed.
   This allows evaluation contexts to produce printed results in :value
   if they so choose, and opt out of the printing here."
  [h]
  (fn [{:keys [op ^Transport transport] :as msg}]
    (h (assoc msg
              :transport (reify Transport
                           (recv [this] (.recv transport))
                           (recv [this timeout] (.recv transport timeout))
                           (send [this {:keys [printed-value value] :as resp}]
                             (.send transport
                                    (if (and printed-value (string? value))
                                      (dissoc resp :printed-value)
                                      (if-let [[_ v] (find resp :value)]
                                        (assoc resp
                                               :value (let [repr (java.io.StringWriter.)]
                                                        (if *print-dup*
                                                          (print-dup v repr)
                                                          (print-method v repr))
                                                        (str repr)))
                                        resp)))
                             this))))))

(set-descriptor! #'pr-values
                 {:requires #{}
                  :expects #{}
                  :handles {}})
