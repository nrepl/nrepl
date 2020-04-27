(ns ping
  (:require [nrepl.middleware :as middleware :refer [set-descriptor!]]
            [nrepl.misc :refer [response-for]]
            [nrepl.transport :as t]))

(defn wrap-ping
  [h]
  (fn [{:keys [op transport] :as msg}]
    (case op
      "ping"
      (t/send transport (response-for msg {:pong "pong"
                                           :status :done}))
      (h msg))))

(def pong :pong)

(set-descriptor! #'wrap-ping
                 {:requires #{}
                  :expects  #{}
                  :handles  {"ping"
                             {:doc     "Ping"
                              :require {}
                              :returns {"status" "done"}}}})
