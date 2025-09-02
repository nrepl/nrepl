(ns nrepl.middleware.lookup
  "Symbol info lookup middleware.

  It's meant to provide you with useful data like definition location,
  parameter lists, etc.

  The middleware can be configured to use a different lookup
  function via a dynamic variable or a request parameter.

  NOTE: The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:require
   [nrepl.middleware :as middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.transport :as t]
   [nrepl.util.lookup :as lookup]))

(def ^:dynamic *lookup-fn*
  "Function to use for lookup. Takes two arguments:

  * `ns`, the namespace in which to do the lookup.
  * `sym`, the symbol to lookup "
  lookup/lookup)

(defn lookup-reply
  [{:keys [session sym ns lookup-fn] :as msg}]
  (try
    (let [the-ns (if ns (symbol ns) (symbol (str (@session #'*ns*))))
          sym (symbol sym)
          lookup-fn (or (some-> lookup-fn symbol misc/requiring-resolve)
                        (misc/resolve-in-session msg *lookup-fn*))]
      {:status :done, :info (lookup-fn the-ns sym)})
    (catch Exception _
      {:status (cond-> #{:done :lookup-error}
                 (some? ns) (conj :namespace-not-found))})))

(defn wrap-lookup
  "Middleware that provides symbol info lookup.
  It understands the following params:

  * `sym` - the symbol which to lookup.
  * `ns`- the namespace in which to do lookup. Defaults to `*ns*`.
  * `lookup` – a fully-qualified symbol naming a var whose function to use for
  lookup. Must point to a function with signature [sym ns]."
  [h]
  (fn [{:keys [op] :as msg}]
    (if (= op "lookup")
      (t/respond-to msg (lookup-reply msg))
      (h msg))))

(set-descriptor! #'wrap-lookup
                 {:requires #{"clone"}
                  :expects #{}
                  :handles {"lookup"
                            {:doc "Lookup symbol info."
                             :requires {"sym" "The symbol to lookup."}
                             :optional {"ns" "The namespace in which we want to do lookup. Defaults to `*ns*`."
                                        "lookup-fn" "The fully qualified name of a lookup function to use instead of the default one (e.g. `my.ns/lookup`)."}
                             :returns {"info" "A map of the symbol's info."}}}
                  :session-dynvars #{#'*lookup-fn*}})
