(ns nrepl.tls-test
  (:require [clojure.test :refer [deftest is]]
            [com.github.ivarref.locksmith :as locksmith]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [nrepl.tls :as tls]
            [nrepl.tls-client-proxy :as tls-client-proxy]
            [nrepl.test-helpers :refer [eval-value1]]
            [nrepl.transport :as transport])
  (:import (clojure.lang ExceptionInfo)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress Socket)
           (javax.net.ssl SSLException SSLHandshakeException)))

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (locksmith/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

; Make sure TLS is in fact used
(defn tls-connect
  ^AutoCloseable [opts]
  (#'nrepl/tls-connect opts))

(defn println-err [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn noisy-deref [e]
  (let [dereffed (deref e 3000 :timeout)]
    (if (= :timeout dereffed)
      (do
        (println-err "Timeout when trying to deref exception!" e)
        (is false "Timeout when deref exception!")
        nil)
      dereffed)))

(defn tls-socket-exception? [exception-promise]
  (let [e (noisy-deref exception-promise)]
    (instance? SSLException e)))

(deftest bad-config
  (is (thrown? ExceptionInfo (server/start-server :tls? true)))
  (is (= :nrepl.server/invalid-start-request
         (:nrepl/kind (try
                        (server/start-server :tls? true)
                        (catch Throwable t
                          (ex-data t)))))))

(deftest bad-keys
  (let [[server-keys _] (gen-key-pair)
        [_ client-keys] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))
                transport (tls-connect {:tls-keys-str client-keys
                                        :host         "127.0.0.1"
                                        :port         (:port server)
                                        :transport-fn (fn [& args]
                                                        ;; there appears to be
                                                        ;; a (race?) condition
                                                        ;; that the
                                                        ;; transport-fn could
                                                        ;; also be called.
                                                        (try
                                                          (apply transport/bencode args)
                                                          (catch Exception e
                                                            (is (instance? SSLHandshakeException e)))))})]
      (let [client (nrepl/client transport 30000)]
        (is (thrown? Exception (eval-value1 client '(+ 1 1))))
        (is (tls-socket-exception? exception))))))

(deftest bad-keys-then-good
  (let [[server-keys good-client-keys] (gen-key-pair)
        [_ bad-client-keys] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [transport (tls-connect {:tls-keys-str bad-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn (fn [& args]
                                                          ;; there appears to be
                                                          ;; a (race?) condition
                                                          ;; that the
                                                          ;; transport-fn could
                                                          ;; also be called.
                                                          (try
                                                            (apply transport/bencode args)
                                                            (catch Exception e
                                                              (is (instance? SSLHandshakeException e)))))})]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception (eval-value1 client '(+ 1 1))))
          (is (tls-socket-exception? exception))))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2 (eval-value1 client '(+ 1 1)))))))))

(deftest bad-keys-then-good-no-consume-exception
  (let [[server-keys good-client-keys] (gen-key-pair)
        [_ bad-client-keys] (gen-key-pair)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys)]
      (with-open [transport (tls-connect {:tls-keys-str bad-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception (eval-value1 client '(+ 1 1))))))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2 (eval-value1 client '(+ 1 1)))))))))

(deftest conn-close-then-ok
  (let [[server-keys good-client-keys] (gen-key-pair)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys)]
      (with-open [sock (Socket.)]
        (let [addr (InetSocketAddress. "127.0.0.1" ^int (:port server))]
          (.connect sock addr 1000)))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2 (eval-value1 client '(+ 1 1)))))))))

(deftest happy-case
  (let [[server-keys client-keys] (gen-key-pair)]
    (with-open [server (server/start-server :tls? true :tls-keys-str server-keys)
                transport (tls-connect {:tls-keys-str client-keys
                                        :host         "127.0.0.1"
                                        :port         (:port server)
                                        :transport-fn transport/bencode})]
      (let [client (nrepl/client transport 30000)]
        (is (= 2 (eval-value1 client '(+ 1 1))))))))

(deftest regular-connection-times-out
  (let [[server-keys _] (gen-key-pair)
        exception (promise)]
    (with-redefs [tls/*handshake-timeout-ms* 300]
      (with-open [server (server/start-server :tls? true
                                              :tls-keys-str server-keys
                                              :consume-exception (partial deliver exception))
                  ^AutoCloseable _ (nrepl/connect :port (:port server))]
        (is (tls-socket-exception? exception))))))

(deftest regular-connection+eval-fails
  (let [[server-keys _] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))
                ^AutoCloseable transport (nrepl/connect :port (:port server))]
      (let [client (nrepl/client transport 30000)]
        (is (thrown? Exception (eval-value1 client '(+ 1 1))))
        (is (tls-socket-exception? exception))))))

(deftest server-keys-then-good
  (let [[server-keys good-client-keys] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [transport (tls-connect {:tls-keys-str server-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn (fn [& args]
                                                          ;; there appears to be
                                                          ;; a (race?) condition
                                                          ;; that the
                                                          ;; transport-fn could
                                                          ;; also be called.
                                                          (try
                                                            (apply transport/bencode args)
                                                            (catch Exception e
                                                              (is (instance? SSLHandshakeException e)))))})]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception (eval-value1 client '(+ 1 1))))
          (is (tls-socket-exception? exception))))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2 (eval-value1 client '(+ 1 1)))))))))

(deftest tls-client-proxy-test
  (let [[server-keys client-keys] (gen-key-pair)]
    (with-redefs [tls-client-proxy/atomic-println (fn [& _] nil)]
      (with-open [server (server/start-server :tls? true :tls-keys-str server-keys)]
        (let [state (tls-client-proxy/start-tls-proxy {:remote-host  "127.0.0.1"
                                                       :remote-port  (:port server)
                                                       :tls-keys-str client-keys
                                                       :port-file    nil
                                                       :block?       false})]
          (try
            (let [proxy-port (deref (:port-promise @state) 3000 nil)]
              (when (is (> proxy-port 0))
                (with-open [^AutoCloseable transport (nrepl/connect :port proxy-port)]
                  (let [client (nrepl/client transport 30000)]
                    (is (= 2 (eval-value1 client '(+ 1 1))))))))
            (finally
              (tls-client-proxy/stop! state))))))))
