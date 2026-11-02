(ns nrepl.util.session
  "Utilities and functions extracted from `nrepl.middleware.session`
  to prevent a circular dependency between it and `nrepl.transport`.")

(def ^:private sessions
  "Stores the mapping session-id->session for all sessions of the process."
  (atom {}))

(defn resolve-session
  "Return active session for the given `session-id`."
  [session-id]
  (@sessions session-id))

(defn all-session-ids
  "Return the list of all session ids in the current process."
  []
  (or (keys @sessions) []))

(defn register-session
  "Record the provided `session` in the process-wide session registry."
  [session]
  (swap! sessions assoc (:id (meta session)) session))

(defn close-session
  "Close the given session and unregister it."
  [session]
  (let [{:keys [close id]} (meta session)]
    (when close (close))
    (swap! sessions dissoc id)))

(defn close-transport-sessions
  "Attempt to close all sessions that were created from the given
  `transport`."
  [transport]
  (doseq [[_ session] @sessions
          :when (identical? (:transport (meta session)) transport)]
    (close-session session)))
