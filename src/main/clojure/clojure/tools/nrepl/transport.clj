(ns clojure.tools.nrepl.transport
  (:require [clojure.tools.nrepl.bencode :as be]
            [clojure.java.io :as io]
            clojure.walk)
  (:refer-clojure :exclude (send))
  (:import (java.io InputStream OutputStream PushbackInputStream
                    Reader Writer)
           java.net.Socket
           (java.util.concurrent BlockingQueue LinkedBlockingQueue
                                 SynchronousQueue TimeUnit)))

(defprotocol Transport
  "Defines the interface for a wire protocol implementation for use
   with nREPL."
  (recv [this] [this timeout]
    "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
  (send [this msg] "Sends msg."))

(defprotocol CloseableTransport
  (close [this])
  (closed? [this]))

(defn- closeable?
  [fn]
  (when-not fn (throw (UnsupportedOperationException. "Transport is not closeable"))))

(deftype FnTransport [recv-fn send-fn close closed?]
  Transport
  (send [this msg] (-> msg clojure.walk/stringify-keys send-fn) this)
  (recv [this] (.recv this Long/MAX_VALUE))
  (recv [this timeout] (clojure.walk/keywordize-keys (recv-fn timeout)))
  CloseableTransport
  (closed? [this] (closeable? closed?) (closed?))
  (close [this] (closeable? close) (close)))

(defn fn-transport
  ([read write] (fn-transport read write nil nil))
  ([read write close closed?]
    (let [read-queue (SynchronousQueue.)]
      (future (while true
                (.put read-queue (read))))
      (FnTransport.
        #(.poll read-queue % TimeUnit/MILLISECONDS)
        write
        close
        closed?))))

(defn bencode
  ([^Socket s] (bencode s s s))
  ([in out & [^Socket s]]
    (let [in (PushbackInputStream. (io/input-stream in))
          out (io/output-stream out)]
      (fn-transport
        #(be/read-bencode in)
        #(locking out
           (doto out
             (be/write-bencode %)
             .flush))
        (when s #(.close s))
        (when s #(.isClosed s))))))

; TODO bad name?
(defn terminal
  ([^Socket s] (terminal s s s))
  ([in out & [^Socket s]]
    (let [in (io/reader in)
          out (io/writer out)
          ; TODO
          write #()
          read #()]
      (fn-transport read write            
        (when s #(.close s))
        (when s #(.isClosed s))))))

(deftype QueueTransport [^BlockingQueue in ^BlockingQueue out]
  Transport
  (send [this msg] (.put out msg))
  (recv [this] (.take in))
  (recv [this timeout] (.poll in timeout TimeUnit/MILLISECONDS)))

(defn piped-transports
  "Returns a pair of Transports that read from and write to each
   other.  Probably only useful in testing."
  []
  (let [a (LinkedBlockingQueue.)
        b (LinkedBlockingQueue.)]
    [(QueueTransport. a b) (QueueTransport. b a)]))
