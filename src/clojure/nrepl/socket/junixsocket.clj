(ns nrepl.socket.junixsocket
  "Should only be required if junixsocket is present on the classpath."
  (:import
   (java.io File)
   (org.newsclub.net.unix AFUNIXServerSocketChannel AFUNIXSocketAddress
                          AFUNIXSocketChannel)))

(defn unix-server-socket
  "Return a server filesystem socket bound to the given path."
  [^String path]
  (let [addr (AFUNIXSocketAddress/of (File. path))
        socket-chan (AFUNIXServerSocketChannel/open)]
    (.bind socket-chan addr)
    (.deleteOnExit (File. (.getPath addr)))
    socket-chan))

(defn unix-client-socket
  "Returns a client filesystem socket bound to the given path."
  [^String path]
  (let [addr (AFUNIXSocketAddress/of (File. path))
        socket-chan (AFUNIXSocketChannel/open)]
    (.connect socket-chan addr)
    socket-chan))

(defn get-socket-address-path
  "Given a socket address instance, return its filesystem path as a string."
  [^AFUNIXSocketAddress socket-addr]
  (.getPath socket-addr))
