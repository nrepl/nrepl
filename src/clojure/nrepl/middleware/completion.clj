(ns nrepl.middleware.completion
  "Code completion middleware.

  The middleware is a simple wrapper around the
  functionality in `nrepl.completion`. Its
  API is inspired by cider-nrepl's \"complete\" middleware.

  The middleware can be configured to use a different completion
  function via a dynamic variable or a request parameter.

  NOTE: The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:require
   [nrepl.util.completion :as complete]
   [nrepl.middleware :as middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for] :as misc]
   [nrepl.transport :as t])
  (:import nrepl.transport.Transport))

(def ^:dynamic *complete-fn*
  "Function to use for completion. Takes three arguments: `prefix`, the completion prefix,
  `ns`, the namespace in which to look for completions, and `options`, a map of additional
  options for the completion function."
  complete/completions)

(defn completion-reply
  [{:keys [session prefix ns complete-fn options] :as msg}]
  (let [ns (if ns (symbol ns) (symbol (str (@session #'*ns*))))
        completion-fn (or (and complete-fn (misc/requiring-resolve (symbol complete-fn))) *complete-fn*)]
    (try
      (response-for msg {:status :done :completions (completion-fn prefix ns options)})
      (catch Exception e
        (response-for msg {:status #{:done :completion-error}})))))

(defn wrap-completion
  "Middleware that provides code completion.
  It understands the following params:

  * `prefix` - the prefix which to complete.
  * `ns`- the namespace in which to do completion. Defaults to `*ns*`.
  * `complete-fn` – a fully-qualified symbol naming a var whose function to use for
  completion. Must point to a function with signature [prefix ns options].
  * `options` – a map of options to pass to the completion function."
  [h]
  (fn [{:keys [op ^Transport transport] :as msg}]
    (if (= op "completions")
      (t/send transport (completion-reply msg))
      (h msg))))

(set-descriptor! #'wrap-completion
                 {:requires #{"clone"}
                  :expects #{}
                  :handles {"completions"
                            {:doc "Provides a list of completion candidates."
                             :requires {"prefix" "The prefix to complete."}
                             :optional {"ns" "The namespace in which we want to obtain completion candidates. Defaults to `*ns*`."
                                        "complete-fn" "The fully qualified name of a completion function to use instead of the default one (e.g. `my.ns/completion`)."
                                        "options" "A map of options supported by the completion function."}
                             :returns {"completions" "A list of completion candidates. Each candidate is a map with `:candidate` and `:type` keys. Vars also have a `:ns` key."}}}})
