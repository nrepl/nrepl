(ns nrepl.socket.dynamic
  "Socket-related code that depends on classes that are only known at
  run time, not complile time.  This just allows us to isolate
  reflections we can't avoid, so that we can easily ask eastwood to
  ignore them.  This namespace should only be needed until JDK 16+ can
  be assumed.")

(set! *warn-on-reflection* false)

;; SocketAddress doesn't have .getPath until JDK 16, and we can't refer to
;; AFUnixSocketAddress unconditionally in the junixsocket cases.  Also note that
;; the former returns a Path, and the latter returns a string.

(defn get-path [addr] (.getPath addr))
