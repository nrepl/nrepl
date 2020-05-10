(ns ping
  (:require [nrepl.middleware :as middleware :refer [set-descriptor!]]
            [nrepl.misc :as misc :refer [response-for]]
            [nrepl.transport :as t]))

(def deferred-handler
  (delay
    (fn [{:keys [transport] :as msg}]
      (t/send transport (response-for msg
                                      {:pong
                                       ((misc/requiring-resolve (symbol "ping-imp/pong")))
                                       :status :done})))))

(defn wrap-ping
  [h]
  (fn [{:keys [op transport] :as msg}]
    (case op
      "ping"
      (t/send transport (response-for msg {:pong   "pong"
                                           :status :done}))
      "deferred-ping"
      (@deferred-handler msg)

      (h msg))))

(set-descriptor! #'wrap-ping
                 {:requires #{}
                  :expects  #{}
                  :handles  {"ping"
                             {:doc      "Ping"
                              :requires {}
                              :returns  {"status" "done"}}
                             "deferred-ping"
                             {:doc      "Ping"
                              :requires {}
                              :returns  {"status" "done"}}}})
