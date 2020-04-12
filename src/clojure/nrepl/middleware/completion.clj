(ns nrepl.middleware.completion
  "Code completion middleware."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:require
   [nrepl.completion :as complete]
   [nrepl.middleware :as middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t])
  (:import nrepl.transport.Transport))

(defn completion-reply
  [{:keys [prefix ns] :as msg}]
  (let [ns (if ns (symbol ns) *ns*)]
    (try
      (response-for msg {:status :done :completions (complete/completions prefix ns)})
      (catch Exception e
        (response-for msg  {:status #{:done :completion-error}})))))

(defn wrap-completion
  "Middleware that provides code completion."
  [h]
  (fn [{:keys [op ^Transport transport] :as msg}]
    (if (not= op "completions")
      (h msg)
      (h (t/send transport (completion-reply msg))))))

(set-descriptor! #'wrap-completion
                 {:requires #{"clone"}
                  :expects #{}
                  :handles {"completions"
                            {:doc "Provides a list of completion candidates."
                             :requires {"prefix" "The prefix to complete."}
                             :optional {"ns" "The ns in which we want to obtain the candidates. Defaults to *ns*."}
                             :returns "A list of completion candidates. Each candidate is a map with with :candidate and :type keys. Vars also have a :ns key."}}})
