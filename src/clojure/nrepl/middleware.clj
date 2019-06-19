(ns nrepl.middleware
  (:refer-clojure :exclude [comparator])
  (:require
   [clojure.set :as set]
   [nrepl.misc :as misc]
   [nrepl.transport :as transport]
   [nrepl.version :as version]))

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
                                                   {middleware-var descriptor}) %))))

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
                                                   (merge
                                                    (when-let [aux (reduce
                                                                    (fn [aux {:keys [describe-fn]}]
                                                                      (if describe-fn
                                                                        (merge aux (describe-fn msg))
                                                                        aux))
                                                                    nil
                                                                    (vals descriptors))]
                                                      {:aux aux})
                                                    {:ops (let [ops (apply merge (map :handles (vals descriptors)))]
                                                            (if verbose?
                                                              ops
                                                              (zipmap (keys ops) (repeat {}))))
                                                     :versions {:nrepl (safe-version version/version)
                                                                :clojure (safe-version
                                                                          (assoc *clojure-version* :version-string (clojure-version)))
                                                                :java (safe-version (java-version))}
                                                     :status :done})))
      (h msg))))

(set-descriptor! #'wrap-describe
                 {:handles {"describe"
                            {:doc "Produce a machine- and human-readable directory and documentation for the operations supported by an nREPL endpoint."
                             :requires {}
                             :optional {"verbose?" "Include informational detail for each \"op\"eration in the return message."}
                             :returns {"ops" "Map of \"op\"erations supported by this nREPL endpoint"
                                       "versions" "Map containing version maps (like *clojure-version*, e.g. major, minor, incremental, and qualifier keys) for values, component names as keys. Common keys include \"nrepl\" and \"clojure\"."
                                       "aux" "Map of auxiliary data contributed by all of the active nREPL middleware via :describe-fn functions in their descriptors."}}}})
;; eliminate implicit expectation of "describe" handler; this is the only
;; special case introduced by the conj'ing of :expects "describe" by set-descriptor!
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
        (printf "[WARNING] No nREPL middleware descriptor in metadata of %s, see nrepl.middleware/set-descriptor!" m)
        (println)))
    (let [middlewares (set (for [m middlewares]
                             (-> (descriptor m)
                                 ;; only conj'ing m here to support direct reference to
                                 ;; middleware dependencies in :expects and :requires,
                                 ;; e.g. interruptable-eval's dep on
                                 ;; nrepl.middleware.print/wrap-print
                                 (update-in [:handles] (comp set #(conj % m) keys))
                                 (assoc :implemented-by m))))]
      (set (for [m middlewares]
             (reduce
              #(update-in % [%2] into (dependencies middlewares % %2))
              m #{:expects :requires}))))))

(defn- topologically-sort
  "Topologically sorts the given middlewares according to the comparator,
  with the added heuristic that any middlewares that have no dependencies
  will be sorted toward the end."
  [komparator stack]
  (let [stack (vec stack)
        ;; using indexes into the above vector as the vertices in the
        ;; graph algorithm, will translate back into middlewares at
        ;; the end.
        vertices (range (count stack))
        edges (for [i1 vertices
                    i2 (range i1)
                    :let [x (komparator (stack i1) (stack i2))]
                    :when (not= 0 x)]
                (if (neg? x) [i1 i2] [i2 i1]))
        ;; the trivial vertices have no connections, and we pull them
        ;; out here so we can make sure they get put on the end
        trivial-vertices (remove (set (apply concat edges)) vertices)]
    (loop [sorted-vertices []
           remaining-edges edges
           remaining-vertices (remove (set trivial-vertices) vertices)]
      (if (seq remaining-vertices)
        (let [non-initials (->> remaining-edges
                                (map second)
                                (set))
              next-vertex (->> remaining-vertices
                               (remove non-initials)
                               (first))]
          (if next-vertex
            (recur (conj sorted-vertices next-vertex)
                   (remove #((set %) next-vertex) remaining-edges)
                   (remove #{next-vertex} remaining-vertices))
            ;; Cycle detected! Have to actually assemble a cycle so we
            ;; can throw a useful error.
            (let [start (first remaining-vertices)
                  step (into {} remaining-edges)
                  cycle (->> (iterate step start)
                             (rest)
                             (take-while (complement #{start}))
                             (cons start))
                  data {:cycle (map stack cycle)}]
              (throw (ex-info
                      "Unable to satisfy nREPL middleware ordering requirements!"
                      data)))))
        (map stack (concat sorted-vertices trivial-vertices))))))

(defn linearize-middleware-stack
  [middlewares]
  (->> middlewares
       extend-deps
       (topologically-sort comparator)
       (map :implemented-by)))
