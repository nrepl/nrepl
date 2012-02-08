(ns clojure.tools.nrepl.server
  (:require [clojure.tools.nrepl :as repl]
            (clojure.tools.nrepl handlers
                                 [transport :as transport])
            [clojure.tools.logging :as log])
  (:use [clojure.tools.nrepl.misc :only (returning response-for)])
  (:import (java.util.concurrent Future TimeUnit TimeoutException)
           (java.net Socket ServerSocket)))

; could be a lot fancier, but it'll do for now
(def ^{:private true} ack-port-promise (atom nil))

(defn reset-ack-port!
  []
  (reset! ack-port-promise (promise))
  ; save people the misery of ever trying to deref the empty promise in their REPL
  nil)

(defn ack
  [h]
  (fn [{:keys [op port transport] :as msg}]
    (if (= op "ack")
      (do
        (deliver @ack-port-promise (Integer/parseInt port))
        (transport/send transport (response-for msg {:status :done})))
      (h msg))))

(defn wait-for-ack
  "Waits for a presumably just-launched nREPL server to connect and
   deliver its port number.  Returns that number if it's delivered
   within `timeout` ms, otherwise nil.  Assumes that `ack`
   middleware has been applied to the local nREPL server handler.

   Expected usage:

   (reset-ack-port!)
   (start-server already-running-server-port)
   => (wait-for-ack)
   59872 ; the port of the server started via start-server"
  [timeout]
  (let [^Future f (future @@ack-port-promise)]
    (try
      ; no deref with timeout in 1.2
      (.get f timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e))))

(defn send-ack
  [my-port ack-port]
  (with-open [session (repl/connect ack-port)]
    ;; TODO 
    (eval session (pr-str `(deliver @@#'ack-port-promise ~my-port)))))

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
  [{:keys [^ServerSocket ss transport-fn handler] :as server-state}]
  (returning server-state
    (when-not (.isClosed ss)
      (let [sock (.accept ss)
            transport (transport-fn sock)]
        (future (with-open [sock sock] (handle handler transport)))
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
  [& {:keys [port transport-fn handler ack-port] :or {port 0}}]
  (let [smap {:ss (ServerSocket. port)
              :transport-fn (or transport-fn transport/bencode) 
              :handler (or handler
                           (clojure.tools.nrepl.handlers/default-handler))}
        server (proxy [clojure.lang.Agent java.io.Closeable] [smap]
                 (close [] (stop-server this)))]
    (send-off server accept-connection)
    (when ack-port
      (send-ack (.getLocalPort (:ss @server)) ack-port))
    server))
