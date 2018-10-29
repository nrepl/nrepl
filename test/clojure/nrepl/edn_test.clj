(ns nrepl.edn-test
  (:require [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [nrepl.transport :as transport]))

(deftest edn-transport-communication
  (is (= (with-open [server (server/start-server :transport-fn transport/nrepl+edn :port 7889)]
           (with-open [conn (nrepl/url-connect "edn://localhost:7889/repl")]
             (-> (nrepl/client conn 1000)
                 (nrepl/message {:op "eval" :code "(+ 2 3)"})
                 nrepl/response-values)))
         [5])))
