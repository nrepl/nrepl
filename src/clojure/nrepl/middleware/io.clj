(ns nrepl.middleware.io
  "Forward output from `System/out`, `System/err`, and root `*out*` and `*err*`
  to clients that requested such forwarding."
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.transport :as t :refer [safe-handle]]
   [nrepl.util.out :as out]
   [nrepl.util.session :as session-util]))

(defn forward-system-output-reply
  "Starts sending what's written to `System/out` and `System/err` as responses
  to `msg`, until its session is closed."
  [{:keys [session] :as msg}]
  (let [session-id (:id (meta session))]
    ;; Closing the session is what stops the forwarding, and the one-off
    ;; session of a request that named none is never closed.
    (if-not (session-util/resolve-session session-id)
      {:status #{:error :unknown-session :done}}
      (do
        ;; Idempotent - safe to call multiple times.
        (out/wrap-standard-streams)
        (doseq [stream [:out :err]]
          (out/set-callback stream session-id #(t/respond-to msg {stream %
                                                                  :source :system})))
        {:status :done}))))

(defn wrap-out
  "Enable forwarding of System/out and System/err output to client. To cancel
  forwarding, close the session it was requested in.

  NB: do not enable stdout forwarding if the client runs inside the same process
  as the server. This will lead to an infinite loop."
  [handler]
  (fn [msg]
    (safe-handle msg
      "forward-system-output" forward-system-output-reply
      :else handler)))

(set-descriptor! #'wrap-out
                 {:requires #{"clone"}
                  :expects #{"eval"}
                  :handles {"forward-system-output"
                            {:doc "Enable forwarding of System/out and System/err output to client. The output keeps arriving as responses to this request, after its 'done', until the session is closed. This is a Clojure-specific op, not part of general nREPL spec."
                             :requires {"session" "The ID of the session to forward the output to. Closing it is what stops the forwarding."}
                             :optional {}
                             :returns {"out" "Content written to System/out."
                                       "err" "Content written to System/err."
                                       "source" "'system' on every forwarded `out` and `err`, to tell them apart from the output of evaluations."
                                       "status" "'unknown-session' if the session could not be resolved or the request names none"}}}})
