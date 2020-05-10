(ns nrepl.middleware.dynamic-loader
  "Support the ability to interactively update the middleware of the *running*
  nREPL server. This can be used by tools to configure an existing instance of
  an environment after connection.

  It can also be used to load extra namespaces, in addition to the ones that new
  middleware are defined in, to handle existing middleware that performs
  deferred loading.

  When combined with the sideloader, this could be used to inject middleware
  that are unknown to the server prior to connection."
  {:author "Shen Tian"
   :added  "0.8"}
  (:require [clojure.string :as str]
            [nrepl.middleware :refer [linearize-middleware-stack set-descriptor!]]
            [nrepl.middleware.session :as middleware.session]
            [nrepl.misc :as misc :refer [response-for with-session-classloader]]
            [nrepl.transport :as t]))

(def ^:dynamic *state* nil)

(defn unknown-op
  "Sends an :unknown-op :error for the given message."
  [{:keys [op transport] :as msg}]
  (t/send transport (response-for msg :status #{:error :unknown-op :done} :op op)))

(defn- update-stack!
  [session middleware]
  (with-session-classloader session
    (let [resolved (map (fn [middleware-str-or-var]
                          (if (var? middleware-str-or-var)
                            middleware-str-or-var
                            (-> middleware-str-or-var
                                (str/replace "#'" "")
                                symbol
                                misc/requiring-resolve)))
                        middleware)
          stack    (linearize-middleware-stack resolved)]
      (if (every? some? resolved)
        (reset! *state* {:handler ((apply comp (reverse stack)) unknown-op)
                         :stack   stack})
        {:unresolved (keep (fn [[m resolved]]
                             (when (nil? resolved) m))
                           (zipmap middleware resolved))}))))

(defn- require-namespaces
  [session namespaces]
  (with-session-classloader session
    (run! (fn [namespace]
            (try
              (require (symbol namespace))
              (catch Throwable _ nil)))
          namespaces)))

(defn wrap-dynamic-loader
  "The dynamic loader is both part of the middleware stack, but is also able to
  modify the stack. To further complicate things, the middleware architecture
  works best when each middleware is a var, resolving to an 1-arity function.

  The state of the external world is thus passed to this middleware by rebinding
  the `*state*` var, and we expect this to have two keys:

  - `:handler`, the current active handler
  - `:stack`, a col of vars that represent the current middleware stack.

  Note that if `*state*` is not rebound, this middleware will not work."
  [h]
  (fn [{:keys [op transport session middleware extra-namespaces] :as msg}]
    (when-not (instance? clojure.lang.IAtom *state*)
      (throw (ex-info "dynamic-loader/*state* is not bond to an atom. This is likely a bug" nil)))
    (case op
      "add-middleware"
      (do
        (require-namespaces session extra-namespaces)
        (let [{:keys [unresolved]}
              (update-stack! session (concat middleware (:stack @*state*)))]
          (if-not unresolved
            (t/send transport (response-for msg {:status :done}))
            (t/send transport (response-for msg {:status                #{:done :error}
                                                 :unresolved-middleware unresolved})))))

      "swap-middleware"
      (do
        (require-namespaces session extra-namespaces)
        (let [{:keys [unresolved]} (update-stack! session middleware)]
          (when transport
            (if-not unresolved
              (t/send transport (response-for msg {:status :done}))
              (t/send transport (response-for msg {:status                #{:done :error}
                                                   :unresolved-middleware unresolved}))))))

      "ls-middleware"
      (t/send transport (response-for msg
                                      :middleware (mapv str (:stack @*state*))
                                      :status :done))

      (h msg))))

(def ^{:private true} add-swap-ops
  {:requires {"middleware" "a list of middleware"}
   :optional {"extra-namespaces" "a list of extra namespaces to load. This is useful when the new middleware feature deferred loading"}
   :returns  {"status"                "`done`, once done, and `error`, if there's any problems in loading a middleware"
              "unresolved-middleware" "List of middleware that could not be resolved"}})

(set-descriptor! #'wrap-dynamic-loader
                 {:requires #{#'middleware.session/session}
                  :expects  #{}
                  :handles  {"ls-middleware"
                             {:doc      "List of current middleware"
                              :requires {}
                              :returns  {"middleware" "list of vars representing loaded middleware, from inside out"}}
                             "add-middleware"
                             (merge add-swap-ops
                                    {:doc "Adding some middleware"})
                             "swap-middleware"
                             (merge add-swap-ops
                                    {:doc "Replace the whole middleware stack"})}})
