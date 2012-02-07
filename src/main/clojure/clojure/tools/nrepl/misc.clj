(ns clojure.tools.nrepl.misc)

(defmacro returning
  "Executes `body`, returning `x`."
  [x & body]
  `(let [x# ~x] ~@body x#))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn response-for
  "Returns a map containing the :session and :id from the 'request' `msg`
   as well as all entries specified in `response-data`, which can be one
   or more maps (which will be merged), *or* key-value pairs.

   (response-for msg :status :done :value \"5\")
   (response-for msg {:status :interrupted})"
  [msg & response-data]
  {:pre [(seq response-data)]}
  (let [{:keys [status] :as response} (if (map? (first response-data))
                                    (reduce merge response-data)
                                    (apply hash-map response-data))
        response (if (not status)
                   response
                   (assoc response :status (if (coll? status)
                                             status
                                             #{status})))]
    (-> (select-keys msg [:session :id])
      ; TODO ugh, this is horrible
      (update-in [:session] #(if (instance? clojure.lang.Agent %)
                               (-> % meta :id)
                               %))
      (merge response))))