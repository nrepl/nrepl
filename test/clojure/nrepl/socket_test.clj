(ns nrepl.socket-test
  (:require [clojure.test :refer [deftest is]]
            [nrepl.socket :as socket])
  (:import (java.net InetSocketAddress ServerSocket Socket)))

(deftest accept-sets-tcp-nodelay-test
  (with-open [^ServerSocket server (socket/inet-socket "127.0.0.1" 0)
              client (Socket.)]
    (.connect client (InetSocketAddress. "127.0.0.1" (.getLocalPort server)))
    (with-open [^Socket accepted (socket/accept server)]
      (is (.getTcpNoDelay accepted)))))
