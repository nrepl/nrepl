(ns clojure.tools.nrepl.server
  (:require [clojure.tools.nrepl :as repl]
            (clojure.tools.nrepl handlers
                                 [ack :as ack]
                                 [transport :as transport]))
  (:use [clojure.tools.nrepl.misc :only (returning response-for log)])
  (:import (java.net Socket ServerSocket)))

(defn handle
  "Handles requests received via `transport` using `handler`.
   Returns nil when `recv` returns nil for the given transport."
  [handler transport]
  (when-let [msg (transport/recv transport)]
    (try
      (handler (assoc msg :transport transport))
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
  (send-off server #(returning % (.close (:ss %)))))

(defn start-server
  "Starts a socket-based nREPL server.  Configuration includes:
 
   * :port — defaults to 0, which autoselects an open port on localhost
   * :handler — the nREPL message handler to use; defaults to the result
       of (clojure.tools.nrepl.handlers/default-handler)
   * :transport-fn — a function that, given a java.net.Socket corresponding
       to an incoming connection, will return an value satisfying the
       clojure.tools.nrepl.Transport protocol.
   * :ack-port — if specified, the port of an already-running server
       that will be connected to to inform of the new server's port.
       Useful only by Clojure tooling implementations.

   Returns a handle to the server that is started, which may be stopped
   either via `stop-server`, (.close server), or automatically via `with-open`."
  [& {:keys [port transport-fn handler ack-port greeting-fn] :or {port 0}}]
  (let [smap {:ss (ServerSocket. port)
              :transport (or transport-fn transport/bencode)
              :greeting greeting-fn
              :handler (or handler
                           (clojure.tools.nrepl.handlers/default-handler))}
        server (proxy [clojure.lang.Agent java.io.Closeable] [smap]
                 (close [] (stop-server this)))]
    (send-off server accept-connection)
    (when ack-port
      (ack/send-ack (.getLocalPort (:ss @server)) ack-port))
    server))
