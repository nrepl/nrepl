(ns nrepl.tls-client-proxy
  "Proxy a local regular TCP connection to a remote TLS TCP connection.
  Can be used if your editor/IDE does not (yet) support TLS connections.

  Example usage:
  $ clojure -Sdeps '{:aliases {:proxy {:deps {nrepl/nrepl {:mvn/version \"1.2.0\"}} :exec-fn nrepl.tls-client-proxy/start-tls-proxy :exec-args {:remote-host \"localhost\" :remote-port 4001 :bind 9001 :tls-keys-file \"client.keys\"}}}}' -T:proxy

  This will start a standalone program that will forward local TCP connections on 127.0.0.1:9001
  to the remote TLS TCP connection at 127.0.0.1:4001 using the key provided in the file `client.keys`."
  {:added "1.1"}
  (:require [nrepl.tls :as tls]
            [nrepl.socket :as socket]
            [nrepl.cmdline :as cmdline])
  (:import (java.net Socket ServerSocket)
           (java.io IOException BufferedInputStream BufferedOutputStream Closeable InputStream OutputStream)
           (java.time.format DateTimeFormatter)
           (java.time ZonedDateTime)))

(def ^:private ^DateTimeFormatter pattern (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z"))

(defonce ^:private lock (Object.))

(defn- atomic-println [& args]
  (locking lock
    (apply println args)))

(defn- info [& args]
  (apply atomic-println
         (into [(.format pattern (ZonedDateTime/now))
                (str "[" (.getName (Thread/currentThread)) "]")
                "INFO"]
               args)))

(defn- warn [& args]
  (apply atomic-println
         (into [(.format pattern (ZonedDateTime/now))
                (str "[" (.getName (Thread/currentThread)) "]")
                "WARN"]
               args)))

(defn- error [& args]
  (binding [*out* *err*]
    (apply atomic-println
           (into [(.format pattern (ZonedDateTime/now))
                  (str "[" (.getName (Thread/currentThread)) "]")
                  "ERROR"]
                 args))))

(defn- add-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (.print System/err "Uncaught exception on ")
       (.println System/err (.getName ^Thread thread))
       (.printStackTrace ^Throwable ex)
       (error "Uncaught exception on" (.getName ^Thread thread))
       nil))))

(defn- close [s]
  (when s
    (try
      (.close ^Closeable s)
      (catch IOException _
        nil))))

(defn- running? [state]
  (when state
    (:running? @state)))

(defn- accept [state ^ServerSocket server]
  (try
    (.accept server)
    (catch Exception e
      (when (running? state)
        (error "Error during .accept:" (.getMessage e)))
      nil)))

(defmacro socket-thread [state sock other-sock f]
  `(let [state# ~state]
     (future
       (try
         (swap! state# update :threads (fnil conj #{}) (Thread/currentThread))
         (let [sock# ~sock
               other-sock# ~other-sock
               f# ~f]
           (try
             (swap! state# update :sockets (fnil conj #{}) sock#)
             (f# sock#)
             (finally
               (close sock#)
               (close other-sock#)
               (swap! state# update :sockets (fnil disj #{}) sock#))))
         (catch Throwable t#
           (error "Unhandled exception:" (.getMessage t#))
           (swap! state# update :unhandled-exceptions (fnil conj #{}) t#))
         (finally
           (swap! state# update :threads (fnil disj #{}) (Thread/currentThread)))))))

(defn- read-socket [state ^InputStream inp]
  (try
    (let [v (.read inp)]
      (when (not= v -1)
        v))
    (catch Exception e
      (when (running? state)
        (warn "Exception while reading from socket:" (.getMessage e)))
      nil)))

(defn- write-socket [state ^OutputStream out rd flush?]
  (try
    (.write out ^int rd)
    (when flush?
      (.flush out))
    true
    (catch Exception e
      (when (running? state)
        (warn "Exception while writing to socket:" (.getMessage e)))
      nil)))

(defn- available? [state ^InputStream inp]
  (try
    (let [v (.available inp)]
      (> v 0))
    (catch Exception e
      (when (running? state)
        (warn "Exception while calling .available:" (.getMessage e)))
      nil)))

(defn- pump! [state ^Socket src ^Socket dst]
  (try
    (with-open [inp (BufferedInputStream. (.getInputStream src))
                out (BufferedOutputStream. (.getOutputStream dst))]
      (loop []
        (when (and (running? state) (not (.isClosed src)) (not (.isClosed dst)))
          (when-let [rd (read-socket state inp)]
            (when (write-socket state out rd (not (available? state inp)))
              (recur))))))
    (catch Exception e
      (if (running? state)
        (throw e)
        nil))))

(defn stop!
  "Stops all threads and closes all sockets associated with this TLS proxy. Waits for threads to exit."
  [state]
  (swap! state assoc :running? false)
  (while (not-empty (:threads @state))
    (doseq [sock (:sockets @state)]
      (close sock))
    (Thread/sleep 100)))

(defn- proxy-connection [{:keys [tls-context remote-host remote-port connect-timeout-ms]}
                         state sock]
  (when-let [tls-sock (try
                        (tls/socket tls-context remote-host remote-port connect-timeout-ms)
                        (catch Exception e
                          (error "Could not connect to remote"
                                 (str remote-host ":" remote-port)
                                 ". Error message: " (.getMessage e))
                          (close sock)
                          nil))]
    (socket-thread
     state
     sock
     tls-sock
     (fn [_]
       (socket-thread state tls-sock sock (fn [_] (pump! state tls-sock sock)))
       (pump! state sock tls-sock)))))

(defn- run-server [cfg state server-socket]
  (while (running? state)
    (when-let [sock (accept state server-socket)]
      (proxy-connection cfg state sock))))

(defn- err-exit [block? msg]
  (error msg)
  (if (true? block?)
    (do
      (shutdown-agents)
      (System/exit 1))
    (throw msg)))

(defn start-tls-proxy
  "Start a local TLS proxy. This will forward a local regular TCP connection to a remote TLS TCP connection.

  Required options:

  * :remote-host - The host that is hosting a TLS nREPL server
  * :remote-port - The port of the remote host that is hosting a TLS nREPL server
  * One of:
    :tls-keys-file - The TLS certificates and key to use is located in this file
    :tls-keys-str - The TLS certificates and key to use in the form of a string

  Other configuration options include:
  * :port — defaults to 0, which autoselects an open port. The port number will be written to :port-file
  * :bind - bind address, by default \"127.0.0.1\"
  * :port-file - where to write the port number. Defaults to \".nrepl-tls-proxy-port\"
  * :connect-timeout-ms - Connection timeout in milliseconds. Defaults to 10000.
  * :block? - Whether to block or not in this function. Defaults to true. For REPL usage/testing you'll want to set this to false.

  Return value:
  If :block? is false, this function will return a state atom. You can stop this server with `(stop! state)`.
  If :block? is true, i.e. the default value, this function will not return (and exit) before the end user presses Ctrl-C."
  [{:keys [remote-host remote-port tls-keys-str tls-keys-file bind port port-file block? state connect-timeout-ms]
    :or   {bind               "127.0.0.1"
           port               0
           port-file          ".nrepl-tls-proxy-port"
           connect-timeout-ms 10000
           block?             true
           state              (atom {})}}]
  (stop! state)
  (swap! state assoc :running? true)
  (when (empty? remote-host) (err-exit block? ":remote-host not specified"))
  (when (nil? remote-port) (err-exit block? ":remote-port not specified"))
  (let [port-promise (promise)
        block (promise)
        cfg {:remote-host        remote-host
             :remote-port        remote-port
             :connect-timeout-ms connect-timeout-ms
             :tls-context        (try
                                   (tls/ssl-context-or-throw tls-keys-str tls-keys-file)
                                   (catch Exception e
                                     (err-exit block? (str "Could not create TLS context: " (.getMessage e)))))}]
    (swap! state assoc :port-promise port-promise)
    (socket-thread
     state
     (try
       (socket/inet-socket bind port)
       (catch Exception e
         (err-exit block? (str "Could not bind to: " bind ":" port ". Error message: " (.getMessage e)))))
     nil
     (fn [^ServerSocket sock]
       (deliver port-promise (.getLocalPort sock))
       (when port-file
         (spit port-file (str (.getLocalPort sock))))
       (info "Started TLS proxy at"
             (str (str bind ":" (.getLocalPort sock) "."))
             "Proxying to" (str remote-host ":" remote-port "."))
       (run-server cfg state sock)))
    (when block?
      (cmdline/set-signal-handler!
       "INT"
       (fn [_signal]
         (stop! state)
         (deliver block :done)))
      (add-uncaught-exception-handler!)
      @block
      (info "TLS proxy exiting")
      (shutdown-agents))
    state))

(comment
  (do
    (defonce st (atom {}))
    (start-tls-proxy {:port          0
                      :remote-host   "127.0.0.1"
                      :remote-port   5633
                      :tls-keys-file "client.keys"
                      :state         st
                      :block?        false})))
