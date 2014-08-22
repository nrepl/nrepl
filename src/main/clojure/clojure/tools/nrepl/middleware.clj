(ns clojure.tools.nrepl.middleware
  (:require clojure.tools.nrepl
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.misc :as misc]
            [clojure.set :as set])
  (:refer-clojure :exclude (comparator)))

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
  (let [descriptor (-> descriptor
                     (assoc :implemented-by (-> middleware-var var-name symbol))
                     (update-in [:expects] (fnil conj #{}) "describe"))]
    (alter-meta! middleware-var assoc ::descriptor descriptor)
    (alter-var-root middleware-var #(comp (partial wrap-conj-descriptor
                                                   (:handles descriptor)) %))))

(defn- safe-version
  [m]
  (into {} (filter (fn [[_ v]] (or (number? v) (string? v))) m)))

(defn- java-version
  []
  (let [version-string (System/getProperty "java.version")
        version-seq (re-seq #"\d+" version-string)
        version-map (if (<= 3 (count version-seq))
                      (zipmap [:major :minor :incremental :update] version-seq)
                      {})]
    (assoc version-map :version-string version-string)))

(defn wrap-describe
  [h]
  (fn [{:keys [op descriptors verbose? transport] :as msg}]
    (if (= op "describe")
      (transport/send transport (misc/response-for msg
                                  {:ops (if verbose?
                                          descriptors
                                          (into {} (map #(vector (key %) {}) descriptors)))
                                   :versions {:nrepl (safe-version clojure.tools.nrepl/version)
                                              :clojure (safe-version
                                                        (assoc *clojure-version* :version-string (clojure-version)))
                                              :java (safe-version (java-version))}
                                   :status :done}))
      (h msg))))

(set-descriptor! #'wrap-describe
  {:handles {"describe"
             {:doc "Produce a machine- and human-readable directory and documentation for the operations supported by an nREPL endpoint."
              :requires {}
              :optional {"verbose?" "Include informational detail for each \"op\"eration in the return message."}
              :returns {"ops" "Map of \"op\"erations supported by this nREPL endpoint"
                        "versions" "Map containing version maps (like *clojure-version*, e.g. major, minor, incremental, and qualifier keys) for values, component names as keys. Common keys include \"nrepl\" and \"clojure\"."}}}})
; eliminate implicit expectation of "describe" handler; this is the only
; special case introduced by the conj'ing of :expects "describe" by set-descriptor!
(alter-meta! #'wrap-describe update-in [::descriptor :expects] disj "describe")

(defn- dependencies
  [set start dir]
  (let [ops (start dir)
        deps (set/select
               (comp seq (partial set/intersection ops) :handles)
               set)]
    (when (deps start)
      (throw (IllegalArgumentException.
               (format "Middleware %s depends upon itself via %s"
                       (:implemented-by start)
                       dir))))
    (concat ops
            (mapcat #(dependencies set % dir) deps))))

(defn- comparator
  [{a-requires :requires a-expects :expects a-handles :handles}
   {b-requires :requires b-expects :expects b-handles :handles}]
  (or (->> (into {} [[[a-requires b-handles] -1]
                     [[a-expects b-handles] 1]
                     [[b-requires a-handles] 1]
                     [[b-expects a-handles] -1]])
        (map (fn [[sets ret]]
               (and (seq (apply set/intersection sets)) ret)))
        (some #{-1 1}))
      0))

(defn- extend-deps
  [middlewares]
  (let [descriptor #(-> % meta ::descriptor)
        middlewares (concat middlewares
                            (->> (map descriptor middlewares)
                              (mapcat (juxt :expects :requires))
                              (mapcat identity)
                              (filter var?)))]
    (doseq [m (remove descriptor middlewares)]
      (binding [*out* *err*]
        (printf "[WARNING] No nREPL middleware descriptor in metadata of %s, see clojure.tools.middleware/set-descriptor!" m)
        (println)))
    (let [middlewares (set (for [m middlewares]
                             (-> (descriptor m)
                               ; only conj'ing m here to support direct reference to
                               ; middleware dependencies in :expects and :requires,
                               ; e.g. interruptable-eval's dep on
                               ; clojure.tools.nrepl.middleware.pr-values/pr-values
                               (update-in [:handles] (comp set #(conj % m) keys))
                               (assoc :implemented-by m))))]
      (set (for [m middlewares]
             (reduce
               #(update-in % [%2] into (dependencies middlewares % %2))
               m #{:expects :requires}))))))

(defn- conj-sorted
  [stack comparator x]
  (let [comparisons (->> stack
                      (map-indexed #(vector % (comparator x %2)))
                      (remove (comp zero? second)))
        lower (ffirst (filter (comp neg? second) comparisons))
        upper (ffirst (reverse (filter (comp pos? second) comparisons)))
        ; default conj'ing at the end, a good default for descriptor-less middlewares
        [before after] (split-at (or (and upper (inc upper)) lower (count stack)) stack)]
    (into [] (concat before [x] after))))

;; TODO throw exception when the stack doesn't satisfy the requirements of the descriptors involved
(defn linearize-middleware-stack
  [middlewares]
  (->> middlewares
    extend-deps
    (sort-by (comp count (partial apply concat) (juxt :expects :requires)))
    reverse
    (reduce #(conj-sorted % comparator %2) [])
    (map :implemented-by)))

;;; documentation utilities ;;;

; oh, kill me now
(defn- markdown-escape
  [^String s]
  (.replaceAll s "([*_])" "\\\\$1"))

(defn- message-slot-markdown
  [msg-slot-docs]
  (apply str (for [[k v] msg-slot-docs]
               (format "* `%s` %s\n" (pr-str k) (markdown-escape v)))))

(defn- describe-markdown
  "Given a message containing the response to a verbose :describe message,
generates a markdown string conveying the information therein, suitable for
use in e.g. wiki pages, github, etc.

(This is currently private because markdown conversion surely shouldn't
be part of the API here...?)"
  [{:keys [ops versions]}]
  (apply str "# Supported nREPL operations

<small>generated from a verbose 'describe' response (nREPL v"
         (:version-string clojure.tools.nrepl/version)
         ")</small>\n\n## Operations"
         (for [[op {:keys [doc optional requires returns]}] ops]
           (str "\n\n### `" (pr-str op) "`\n\n"
                (markdown-escape doc) "\n\n"
                "###### Required parameters\n\n"
                (message-slot-markdown requires)
                "\n\n###### Optional parameters\n\n"
                (message-slot-markdown optional)
                "\n\n###### Returns\n\n"
                (message-slot-markdown returns)))))
