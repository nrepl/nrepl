(ns ^{:doc "Default server implementations"
      :author "Chas Emerick"}
     clojure.tools.nrepl.server
  (:require [clojure.tools.nrepl :as repl]
            (clojure.tools.nrepl [ack :as ack]
                                 [transport :as t])
            (clojure.tools.nrepl.middleware interruptible-eval
                                            pr-values
                                            session))
  (:use [clojure.tools.nrepl.misc :only (returning response-for log)])
  (:import (java.net Socket ServerSocket InetSocketAddress)))

(defn unknown-op
  "Sends an :unknown-op :error for the given message."
  [transport {:keys [op] :as msg}]
  (t/send transport (response-for msg :status #{:error :unknown-op} :op op)))

(defn handle
  "Handles requests received via `transport` using `handler`.
   Returns nil when `recv` returns nil for the given transport."
  [handler transport]
  (when-let [msg (t/recv transport)]
    (try
      (or (handler (assoc msg :transport transport))
          (unknown-op transport msg))
      (catch Throwable t
        (log t "Unhandled REPL handler exception processing message" msg)))
    (recur handler transport)))

(defn- accept-connection
  [{:keys [^ServerSocket ss transport greeting handler] :as server-state}]
  (returning server-state
    (when-not (.isClosed ss)
      (let [sock (.accept ss)]
        (future (with-open [transport (transport sock)]
                  (when greeting (greeting transport))
                  (handle handler transport)))
        (send-off *agent* accept-connection)))))

(defn stop-server
  "Stops a server started via `start-server`."
  [server]
  (send-off server #(returning % (.close ^ServerSocket (:ss %)))))

(defn default-handler
  "A default handler supporting interruptible evaluation, stdin, sessions, and
   readable representations of evaluated expressions via `pr`."
  []
  (-> (constantly false)
    clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
    clojure.tools.nrepl.middleware.pr-values/pr-values
    clojure.tools.nrepl.middleware.session/add-stdin
    ; output-subscriptions TODO
    clojure.tools.nrepl.middleware.session/session))

;; TODO
#_(defn- output-subscriptions
  [h]
  (fn [{:keys [op sub unsub] :as msg}]
    (case op
      "sub" ;; TODO
      "unsub"
      (h msg))))

(defn start-server
  "Starts a socket-based nREPL server.  Configuration options include:
 
   * :port — defaults to 0, which autoselects an open port on localhost
   * :bind — bind address, by default any (0.0.0.0)
   * :handler — the nREPL message handler to use for each incoming connection;
       defaults to the result of (default-handler)
   * :transport-fn — a function that, given a java.net.Socket corresponding
       to an incoming connection, will return an value satisfying the
       clojure.tools.nrepl.Transport protocol for that Socket.
   * :ack-port — if specified, the port of an already-running server
       that will be connected to to inform of the new server's port.
       Useful only by Clojure tooling implementations.

   Returns a handle to the server that is started, which may be stopped
   either via `stop-server`, (.close server), or automatically via `with-open`."
  [& {:keys [port bind transport-fn handler ack-port greeting-fn] :or {port 0}}]
  (let [bind-addr (if bind (InetSocketAddress. bind port) (InetSocketAddress. port))
        ss (ServerSocket. port 0 (.getAddress bind-addr))
        smap {:ss ss
              :transport (or transport-fn t/bencode)
              :greeting greeting-fn
              :handler (or handler (default-handler))}
        server (proxy [clojure.lang.Agent java.io.Closeable] [smap]
                 (close [] (stop-server this)))]
    (send-off server accept-connection)
    (when ack-port
      (ack/send-ack (.getLocalPort ss) ack-port))
    server))
