(ns nrepl.middleware.io-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [is testing]]
   [nrepl.test-helpers :refer [def-repl-test is+ session-id]]
   [nrepl.transport :as transport]
   [nrepl.util.out :as out]))

(defn- recv-output
  "Reads from `transport` until a message with `text` in its `stream` shows up,
  or nothing has arrived for a second. Other output can be forwarded in
  between, the test reporter's included."
  [transport stream text]
  (loop []
    (when-let [msg (transport/recv transport 1000)]
      (if (some-> (get msg stream) (str/includes? text))
        msg
        (recur)))))

(defn- forwarding?
  "Whether output is being forwarded to the session with `id`."
  [id]
  (boolean (some #(contains? % id) (vals @@#'out/callbacks))))

(def-repl-test forward-system-output
  (let [id (session-id session)]
    (is+ {:status #{:done}} (message! session {:op "forward-system-output"}))
    (testing "output written straight to the system streams reaches the client"
      (.println System/out "to stdout")
      (is+ {:session id} (recv-output transport :out "to stdout"))
      (.println System/err "to stderr")
      (is+ {:session id} (recv-output transport :err "to stderr")))
    (testing "closing the session ends the forwarding"
      (is (forwarding? id))
      (is+ {:status #{:done :session-closed}} (message! session {:op "close"}))
      (is (not (forwarding? id))))))

(def-repl-test forward-system-output-needs-a-session
  ;; A request naming no session runs in a one-off one that's never closed, so
  ;; forwarding to it could never be stopped.
  (let [forwarders #(count (:out @@#'out/callbacks))
        before (forwarders)]
    (is+ {:status #{:done :error :unknown-session}}
         (message! client {:op "forward-system-output"}))
    (is (= before (forwarders)))))
