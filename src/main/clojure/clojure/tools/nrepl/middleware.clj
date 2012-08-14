(ns clojure.tools.nrepl.middleware
  (:require clojure.tools.nrepl
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.misc :as misc]))

(defn- var-name
  [^clojure.lang.Var v]
  (str (.ns v) \/ (.sym v)))

(defn- wrap-conj-descriptor
  [descriptor-map h]
  (fn [{:keys [op descriptors] :as msg}]
    (h (if-not (= op "describe")
         msg
         (assoc msg :descriptors (merge descriptor-map descriptors))))))

(defn set-descriptor!
  "Sets the given [descriptor] map as the ::descriptor metadata on
   the provided [middleware-var], after assoc'ing in the var's
   fully-qualified name as the descriptor's \"implemented-by\" value."
  [middleware-var descriptor]
  (let [descriptor (assoc descriptor "implemented-by" (var-name middleware-var))]
    (alter-meta! middleware-var assoc ::descriptor descriptor)
    (alter-var-root middleware-var #(comp (partial wrap-conj-descriptor (:handles descriptor)) %))))

(defn- safe-version
  [m]
  (into {} (filter (fn [[_ v]] (or (number? v) (string? v))) m)))

(defn wrap-describe
  [h]
  (fn [{:keys [op descriptors verbose? transport] :as msg}]
    (if (= op "describe")
      (transport/send transport (misc/response-for msg
                                  {:ops (if verbose?
                                          descriptors
                                          (into {} (map #(vector (key %) {}) descriptors)))
                                   :versions {:nrepl (safe-version clojure.tools.nrepl/version)
                                              :clojure (safe-version *clojure-version*)}
                                   :status :done}))
      (h msg))))

(set-descriptor! #'wrap-describe
  {:handles {"describe"
             {:doc "Produce a machine- and human-readable directory and documentation for the operations supported by an nREPL endpoint."
              :requires {}
              :optional {"verbose?" "Include informational detail for each \"op\"eration in the return message."}
              :returns {"ops" "Map of \"op\"erations supported by this nREPL endpoint"
                        "versions" "Map containing version maps (like *clojure-version*, e.g. major, minor, incremental, and qualifier keys) for values, component names as keys. Common keys include \"nrepl\" and \"clojure\"."}}}})