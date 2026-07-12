(ns nrepl.tls-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.github.ivarref.locksmith :as locksmith]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [nrepl.test-helpers :refer [eval-value1 win?]]
            [nrepl.tls :as tls]
            [nrepl.tls-client-proxy :as tls-client-proxy]
            [nrepl.transport :as transport])
  (:import (clojure.lang ExceptionInfo)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress Socket)
           (javax.net.ssl SSLException SSLHandshakeException)))

(defn non-windows-fixture
  "TLS tests are currently broken on Windows, so we use this fixture to disable
  them there instead of messing with test selectors."
  [f]
  (if win?
    (is (= 2 (+ 1 1))) ;; Dummy assertion to make the test succeed.
    (f)))

(use-fixtures :each non-windows-fixture)

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

(defn- ctx-error
  "Returns the ExceptionInfo thrown when creating a TLS context from
  `keys-str`, or nil if no exception was thrown."
  ^ExceptionInfo [keys-str]
  (try
    (tls/ssl-context-or-throw keys-str nil)
    nil
    (catch ExceptionInfo e
      e)))

(defn- is-ctx-error
  "Asserts that creating a TLS context from `keys-str` fails with an error
  message containing all of `substrs`."
  [keys-str & substrs]
  (let [e (ctx-error keys-str)]
    (is (some? e))
    (when e
      (doseq [substr substrs]
        (is (str/includes? (.getMessage e) substr))))))

(deftest descriptive-key-material-errors
  (let [{:keys [ca-cert server-cert server-key]} (locksmith/gen-certs {:duration-days 1})]
    (testing "missing private key"
      (is-ctx-error (str ca-cert server-cert) "No private key found"))
    (testing "no certificates"
      (is-ctx-error server-key "No certificates found"))
    (testing "only one certificate"
      (is-ctx-error (str ca-cert server-key) "Only one certificate found"))
    (testing "traditional (non-PKCS#8) private key gets conversion advice"
      (let [sec1-key (str/replace server-key "PRIVATE KEY-----" "EC PRIVATE KEY-----")]
        (is-ctx-error (str ca-cert server-cert sec1-key)
                      "EC PRIVATE KEY format" "openssl pkcs8 -topk8")))
    (testing "password-protected private key"
      (let [enc-key (str/replace server-key "PRIVATE KEY-----" "ENCRYPTED PRIVATE KEY-----")]
        (is-ctx-error (str ca-cert server-cert enc-key) "password-protected")))
    (testing "an unparsable private key reports the attempted algorithms"
      (let [bad-key "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"]
        (is-ctx-error (str ca-cert server-cert bad-key) "Could not parse the private key")))
    (testing "mismatched PEM labels get a structural error instead of \"not found\""
      (let [broken-key (str/replace server-key
                                    "-----END PRIVATE KEY-----"
                                    "-----END RSA PRIVATE KEY-----")]
        (is-ctx-error (str ca-cert server-cert broken-key) "PEM structure")))
    (testing "surrounding whitespace on the PEM fence lines is tolerated"
      (let [indented-key (->> (str/split-lines server-key)
                              (map #(str "  " %))
                              (str/join "\n"))]
        (is (some? (tls/ssl-context-or-throw (str ca-cert server-cert "\n" indented-key "\n") nil)))))
    (testing "the underlying cause is preserved"
      (let [e (ctx-error (str ca-cert server-cert))]
        (is (some? (.getCause e)))))))

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
