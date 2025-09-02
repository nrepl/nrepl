; Original code from https://github.com/aphyr/less-awful-ssl/ :
; Copyright © 2013 Kyle Kingsbury (aphyr@aphyr.com)
; Distributed under the Eclipse Public License, the same as Clojure.

; 2022 Added TLSv1.3, Elliptic Curve support and misc. string utils by Ivar Refsdal (refsdal.ivar@gmail.com)

(ns nrepl.tls
  "Interacting with the Java crypto APIs is one of the worst things you can do
  as a developer. I'm so sorry about all of this."
  {:added "1.1"}
  (:require [clojure.java.io :as io :refer [input-stream]]
            [clojure.stacktrace]
            [clojure.string :as str])
  (:import (java.net InetSocketAddress)
           (java.security KeyFactory
                          KeyStore)
           (java.security.cert Certificate
                               CertificateFactory)
           (java.security.spec PKCS8EncodedKeySpec)
           (javax.net.ssl HandshakeCompletedListener
                          KeyManager
                          KeyManagerFactory
                          SSLContext
                          SSLException SSLHandshakeException
                          SSLSocket
                          TrustManager
                          TrustManagerFactory
                          X509KeyManager
                          X509TrustManager)))

(defmacro base64->binary [string]
  (if (try (import 'java.util.Base64)
           (catch ClassNotFoundException _))
    `(let [^String s# ~string]
       (.decode (java.util.Base64/getMimeDecoder) s#))
    (do
      (import 'javax.xml.bind.DatatypeConverter)
      `(javax.xml.bind.DatatypeConverter/parseBase64Binary ~string))))

(def ^:private ^CertificateFactory x509-cert-factory
  "The X.509 certificate factory"
  (CertificateFactory/getInstance "X.509"))

(def ^:private key-store-password
  "You know, a mandatory password stored in memory so we can... encrypt... data
  stored in memory."
  (char-array "GheesBetDyPhuvwotNolofamLydMues9"))

(defn- get-private-key [^PKCS8EncodedKeySpec spec]
  (reduce (fn [_ keyFactory]
            (try
              (let [kf (KeyFactory/getInstance keyFactory)]
                (reduced (.generatePrivate kf spec)))
              (catch Exception _
                nil)))
          nil
          ["EC" "RSA"]))

(defn- get-parts [s begin? end?]
  (when (string? s)
    (loop [res []
           curr []
           consume? false
           [lin & rst :as lines] (str/split-lines s)]
      (cond (empty? lines)
            res

            (begin? lin)
            (recur res (conj curr lin) true rst)

            (end? lin)
            (recur (conj res (str/join "\n" (conj curr lin))) [] false rst)

            consume?
            (recur res (conj curr lin) true rst)

            (false? consume?)
            (recur res curr false rst)))))

(defn- str->private-key [s]
  (some->> s
           ; LOL Java
           (re-find #"(?ms)^-----BEGIN ?.*? PRIVATE KEY-----$(.+)^-----END ?.*? PRIVATE KEY-----$")
           last
           base64->binary
           PKCS8EncodedKeySpec.
           get-private-key))

(defn- get-certs [cert-str]
  (get-parts cert-str
             (fn [s] (= (str/trim s) "-----BEGIN CERTIFICATE-----"))
             (fn [s] (= (str/trim s) "-----END CERTIFICATE-----"))))

(defn- str->ca-certificate [cert]
  (first (get-certs cert)))

(defn- str->self-certificate [cert]
  (second (get-certs cert)))

(defn- str->certificate
  "Loads an X.509 certificate from a string."
  ^java.security.cert.Certificate
  [tls-keys-str]
  (with-open [stream (input-stream (.getBytes ^String (str->ca-certificate tls-keys-str)))]
    (.generateCertificate x509-cert-factory stream)))

(defn- ^"[Ljava.security.cert.Certificate;" str->certificates
  "Loads an X.509 certificate chain from a string."
  [tls-keys-str]
  (let [self-cert (str->self-certificate tls-keys-str)]
    (with-open [stream (input-stream (.getBytes ^String self-cert))]
      (let [^"[Ljava.security.cert.Certificate;" ar (make-array Certificate 0)]
        (.toArray (.generateCertificates x509-cert-factory stream) ar)))))

(defn- key-store
  "Makes a keystore from a private key and a public certificate"
  [key certs]
  (doto (KeyStore/getInstance (KeyStore/getDefaultType))
    (.load nil nil)
    ; alias, private key, password, certificate chain
    (.setKeyEntry "cert" key key-store-password certs)))

(defn- trust-store
  "Makes a trust store, suitable for backing a TrustManager, out of a CA cert."
  [ca-cert]
  (doto (KeyStore/getInstance "JKS")
    (.load nil nil)
    (.setCertificateEntry "cacert" ca-cert)))

(defn- trust-manager
  "An X.509 trust manager for a KeyStore."
  [^KeyStore key-store]
  (let [factory (TrustManagerFactory/getInstance "PKIX" "SunJSSE")]
    ; I'm concerned that getInstance might return the *same* factory each time,
    ; so we'll defensively lock before mutating here:
    (locking factory
      (->> (doto factory (.init key-store))
           .getTrustManagers
           (filter (partial instance? X509TrustManager))
           first))))

(defn- key-manager
  "An X.509 key manager for a KeyStore."
  ([key-store password]
   (let [factory (KeyManagerFactory/getInstance "SunX509" "SunJSSE")]
     (locking factory
       (->> (doto factory (.init key-store, password))
            .getKeyManagers
            (filter (partial instance? X509KeyManager))
            first))))
  ([key-store]
   (key-manager key-store key-store-password)))

(defn- ssl-context-generator
  "Returns a function that yields SSL contexts. Takes a PKCS8 key file, a
  certificate file, and optionally, a trusted CA certificate used to verify peers."
  ([key certs ca-cert]
   (let [key-manager (key-manager (key-store key certs))
         trust-manager (trust-manager (trust-store ca-cert))]
     (fn build-context []
       (doto (SSLContext/getInstance "TLSv1.3")
         (.init (into-array KeyManager [key-manager])
                (into-array TrustManager [trust-manager])
                nil)))))
  ([key certs]
   (let [key-manager (key-manager (key-store key certs))]
     (fn build-context []
       (doto (SSLContext/getInstance "TLSv1.3")
         (.init (into-array KeyManager [key-manager])
                nil
                nil))))))

(defn- close-silently [^SSLSocket sock]
  (when sock
    (try
      (.close sock)
      nil
      (catch Throwable _
        nil))))

(defn- ssl-str-context
  "Given a string of a PKCS8 key, a certificate file and a trusted CA certificate
  used to verify peers, returns an SSLContext."
  [tls-keys-str]
  (let [key (str->private-key tls-keys-str)
        certs (str->certificates tls-keys-str)
        ca-cert (str->certificate tls-keys-str)]
    ((ssl-context-generator key certs ca-cert))))

(defn ssl-context-or-throw
  "Create a SSL/TLS context from either a string or a file containing two certificates and a private key.
  Throws an exception if the SSL/TLS context could not be created."
  [tls-keys-str tls-keys-file]
  (cond
    (and (some? tls-keys-file) (not (.exists (io/file tls-keys-file))))
    (throw (ex-info (str ":tls-keys-file specified as " tls-keys-file " , but was not found.")
                    {:nrepl/kind :nrepl.server/invalid-start-request}))

    (and (some? tls-keys-file) (.exists (io/file tls-keys-file)))
    (try
      (ssl-str-context (slurp tls-keys-file))
      (catch Exception e
        (throw (ex-info (str "Could not create TLS Context from file " tls-keys-file
                             " . Error message: " (.getMessage e))
                        {:nrepl/kind :nrepl.server/invalid-start-request}))))

    (string? tls-keys-str)
    (try
      (ssl-str-context tls-keys-str)
      (catch Exception e
        (throw (ex-info (str "Could not create TLS Context from string. "
                             "Error message: " (.getMessage e))
                        {:nrepl/kind :nrepl.server/invalid-start-request}))))

    :else
    (throw (ex-info "Could not create TLS Context. Neither :tls-keys-str nor :tls-keys-file given."
                    {:nrepl/kind :nrepl.server/invalid-start-request}))))

(def enabled-protocols
  "An array of protocols we support."
  (into-array String ["TLSv1.3"]))

(defn server-socket
  "Given an SSL context, makes a server SSLSocket."
  ^javax.net.ssl.SSLServerSocket
  [^SSLContext context ^String host port]
  (let [^javax.net.ssl.SSLServerSocket sock (.. context
                                                getServerSocketFactory
                                                createServerSocket)]
    (doto sock
      (.bind (InetSocketAddress. host ^int port))
      (.setNeedClientAuth true)
      (.setReuseAddress true)
      (.setEnabledProtocols enabled-protocols))))

(defn socket
  "Given an SSL context, makes a client SSLSocket."
  ^SSLSocket
  [^SSLContext context ^String host port connect-timeout-ms]
  (let [^SSLSocket sock (-> context
                            .getSocketFactory
                            (.createSocket))]
    (.setEnabledProtocols sock enabled-protocols)
    (.connect sock (InetSocketAddress. host ^int port) ^int connect-timeout-ms)
    sock))

(def ^:dynamic *handshake-timeout-ms*
  "Number of milliseconds to wait for the TLS handshake to complete."
  30000)

(defn accept
  "Accepts a new TLS connection. Waits `*handshake-timeout-ms*` (default: 30 000) milliseconds for the TLS handshake
  to complete. Requires that the client certificate is different from the server certificate."
  [^javax.net.ssl.SSLServerSocket server]
  (let [p (promise)
        ^SSLSocket sock (try
                          (.accept server)
                          (catch Throwable t
                            (if (instance? SSLException t)
                              (throw t)
                              (throw (SSLException. ^Throwable t)))))] ; wrap other exceptions in SSLException to make the server accept loop continue
    (.addHandshakeCompletedListener sock
                                    (reify HandshakeCompletedListener
                                      (handshakeCompleted [_ e]
                                        (if (= (into [] (.getLocalCertificates e))
                                               (into [] (.getPeerCertificates e)))
                                          (deliver p :handshake-bad!)
                                          (deliver p :handshake-ok!)))))
    (future
      (when (= :timeout (deref p *handshake-timeout-ms* :timeout))
        (close-silently sock) ; this will cause .startHandshake to terminate / throw exception
        (deliver p :handshake-timeout!)))
    (try
      (.startHandshake sock) ; can throw IOException
      (let [v @p]
        (cond
          (= v :handshake-bad!)
          (do
            (close-silently sock)
            (throw (SSLHandshakeException. "Cannot use same keys as server")))

          (= v :handshake-timeout!)
          (do
            (close-silently sock)
            (throw (SSLHandshakeException. "TLS handshake timed out")))

          :else
          sock))
      (catch Throwable t
        (deliver p :caught-exception) ; stop the timeout thread
        (close-silently sock)
        (if (instance? SSLException t)
          (throw t)
          (throw (SSLException. ^Throwable t))))))) ; wrap other exceptions in SSLException to make the server accept loop continue
