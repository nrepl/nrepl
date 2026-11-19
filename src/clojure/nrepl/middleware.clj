(ns nrepl.middleware
  "Middleware descriptor infrastructure.

  Provides `set-descriptor!` for declaring middleware metadata
  (dependencies, handled ops, documentation) and
  `linearize-middleware-stack` for ordering middleware according
  to their `:requires` and `:expects` constraints. Also includes
  the built-in `wrap-describe` middleware."
  {:added "0.4"}
  (:refer-clojure :exclude [comparator])
  (:require
   [nrepl.misc :as misc]
   [nrepl.transport :as t :refer [safe-handle]]
   [nrepl.version :as version]))

;; Registering dynvars that are used to configure middleware. This lives here
;; and not in nrepl.middleware.session to avoid circular dependencies.

(def ^:private per-session-dynvars
  "Set of dynamic variables that always have to be established for a new session
  to their initial values."
  (atom #{}))

(defn- var-name
  [^clojure.lang.Var v]
  (str (.ns v) \/ (.sym v)))

(defn set-descriptor!
  "Sets the given [descriptor] map as the ::descriptor metadata on the
  provided [middleware-var], after assoc'ing in the var's fully-qualified name
  as the descriptor's \"implemented-by\" value. The value of `:session-dynvars`
  should be a set of dynamic variables that the middleware expects to be made
  rebindable for each new session.

  Deprecations are declared in the descriptor too, each as a map with any of
  the string entries `:since` (the version it was deprecated in), `:use` (what
  to use instead) and `:note` (anything else worth saying):

  * `:deprecated` at the top level deprecates the middleware as a whole.
  * In the map of an op under `:handles`, `:deprecated` deprecates the op, and
    `:deprecated-params` and `:deprecated-returns` map the names of request
    parameters and response keys to their deprecation.

  They show up in verbose \"describe\" responses, and requests that use a
  deprecated op or parameter are flagged; see `wrap-deprecations`."
  [middleware-var descriptor]
  (let [descriptor (assoc descriptor
                          :implemented-by (-> middleware-var var-name symbol))]
    (swap! per-session-dynvars into (:session-dynvars descriptor))
    (alter-meta! middleware-var assoc ::descriptor descriptor)))

(defn- safe-version
  [m]
  (into {} (filter (fn [[_ v]] (or (number? v) (string? v))) m)))

(defn- java-version
  []
  {:major misc/java-version
   :version-string (System/getProperty "java.version")})

(defn- describe-reply
  [{:keys [verbose?] :as msg} middleware]
  (let [descriptors (mapv (comp ::descriptor meta) middleware)
        ops (let [ops (apply merge (map :handles descriptors))]
              (if verbose?
                ops
                (zipmap (keys ops) (repeat {}))))
        aux (reduce
             (fn [aux {:keys [describe-fn]}]
               (cond-> aux
                 describe-fn (merge (describe-fn msg))))
             nil descriptors)]
    (merge
     {:ops ops
      :middleware (mapv str middleware)
      :versions {:nrepl (safe-version version/version)
                 :clojure (safe-version
                           (assoc *clojure-version* :version-string (clojure-version)))
                 :java (safe-version (java-version))}
      :status :done}
     (when aux {:aux aux}))))

(defn wrap-describe
  "Special middleware that accepts the list of all other middleware (except self)
  and handles `describe` op."
  [h middleware]
  (let [middleware+self (conj middleware #'wrap-describe)]
    (fn [msg]
      (safe-handle msg
        "describe" #(describe-reply % middleware+self)
        :else h))))

(set-descriptor! #'wrap-describe
                 {:handles {"describe"
                            {:doc "Produce a machine- and human-readable directory and documentation for the operations supported by an nREPL endpoint."
                             :requires {}
                             :optional {"verbose?" "Include informational detail for each \"op\"eration in the return message."}
                             :returns {"ops" "Map of \"op\"erations supported by this nREPL endpoint"
                                       "middleware" "Current middleware stack, in inside-out order (earliest middleware is last)."
                                       "versions" "Map containing version maps (like *clojure-version*, e.g. major, minor, incremental, and qualifier keys) for values, component names as keys. Common keys include \"nrepl\" and \"clojure\"."
                                       "aux" "Map of auxiliary data contributed by all of the active nREPL middleware via :describe-fn functions in their descriptors."}}}})

(defn- deprecation-message
  "The sentence to log about `subject` (e.g. \"Op\") `name` being deprecated."
  [subject name {:keys [since use note]}]
  (str subject " " name " is deprecated"
       (when since (str " since " since))
       "."
       (when use (str " Use " use " instead."))
       (when note (str " " note))))

(def ^:private logged-deprecations
  "The deprecations that have made it to the log already, as each one is only
  logged the first time it's run into."
  (atom #{}))

(defn- deprecations-by-op
  "Maps the ops handled by the `stack` to what their descriptors deprecate
  about them, leaving out the ops with nothing deprecated."
  [stack]
  (into {}
        (for [middleware stack
              [op description] (:handles (::descriptor (meta middleware)))
              :let [deprecated (select-keys description [:deprecated :deprecated-params])]
              :when (seq deprecated)]
          [op deprecated])))

(defn- request-deprecations
  "The deprecated things `msg` makes use of, given the deprecations of its op."
  [{:keys [op] :as msg} {:keys [deprecated deprecated-params]}]
  (concat
   (when deprecated
     [(assoc deprecated :kind "op" :name op)])
   (for [[param deprecation] (sort-by key deprecated-params)
         :when (contains? msg (keyword param))]
     (assoc deprecation :kind "param" :name param))))

(defn- done? [{:keys [status]}]
  (some #{:done "done"} (if (coll? status) status [status])))

(defn- deprecation-reporting-transport
  "Wraps `transport` to add `deprecations` to the response that completes the
  request."
  [transport deprecations]
  (reify t/Transport
    (recv [_this] (t/recv transport))
    (recv [_this timeout] (t/recv transport timeout))
    (send [this resp]
      (t/send transport (cond-> resp
                          (done? resp) (assoc :nrepl/deprecations deprecations)))
      this)))

(defn wrap-deprecations
  "Wraps `handler`, which was built from the middleware in `stack`, so that
  requests using an op or a parameter that a descriptor marks as deprecated
  are flagged. For such a request:

  * The response that carries the `done` status also has a
    `:nrepl/deprecations` entry, a list of maps with `:kind` (\"op\" or
    \"param\"), `:name` (for a parameter, of the parameter) and whichever of
    `:since`, `:use` and `:note` the descriptor gives.
  * A warning is logged, the first time each deprecated op or parameter is
    used.

  Returns `handler` itself when the stack deprecates nothing."
  [handler stack]
  (let [by-op (deprecations-by-op stack)]
    (if (empty? by-op)
      handler
      (fn [{:keys [op] :as msg}]
        (let [deprecations (some->> (by-op op) (request-deprecations msg) seq vec)]
          (if-not deprecations
            (handler msg)
            (do
              (doseq [{:keys [kind name] :as deprecation} deprecations
                      :let [id [op kind name]]
                      :when (not (@logged-deprecations id))]
                (swap! logged-deprecations conj id)
                (misc/log :warning
                          (if (= kind "op")
                            (deprecation-message "Op" op deprecation)
                            (deprecation-message (str "Parameter " name " of op") op deprecation))))
              (handler (update msg :transport deprecation-reporting-transport deprecations)))))))))

(defn- comparator
  [{a-requires :requires a-expects :expects a-handles :handles}
   {b-requires :requires b-expects :expects b-handles :handles}]
  (letfn [(intersect? [set1 set2]
            (let [set1 (set set1)]
              (reduce (fn [_ el]
                        (when (contains? set1 el)
                          (reduced true)))
                      nil set2)))]
    (cond (intersect? a-requires b-handles) -1
          (intersect? a-expects b-handles) 1
          (intersect? b-requires a-handles) 1
          (intersect? b-expects a-handles) -1
          :else 0)))

(defn- extended-descriptors
  "This function serves two purposes:
  - Scan through middlewares' dependencies and add those that are mentioned in
  `:requires` or `:expects`, but not included explicitly. This is done for
  compatibility, but will be removed in the future.
  - Return descriptor for each middleware."
  [middlewares]
  (let [mware-set (set middlewares)]
    (into []
          (comp (mapcat (fn [m]
                          (let [{:keys [expects requires] :as desc} (::descriptor (meta m))]
                            (when (nil? desc)
                              (misc/log :warning "No nREPL middleware descriptor in metadata of" m
                                        ", see nrepl.middleware/set-descriptor!"))
                            (when-let [deprecation (:deprecated desc)]
                              (misc/log :warning (deprecation-message "Middleware" m deprecation)))
                            (let [deps (concat expects requires)
                                  missing-deps (for [dep deps
                                                     :when (and (var? dep) (not (mware-set dep)))]
                                                 (do (misc/log :warning (format "Middleware %s is required by %s but is not present in middleware list." dep m))
                                                     dep))]
                              ;; TODO: stop adding missing deps in future versions.
                              (into [m] missing-deps)))))
                (distinct)
                (map (fn [m]
                       (let [desc (::descriptor (meta m))]
                         (-> desc
                             ;; Conj the middleware Var itself to `:handles` to support direct
                             ;; reference to middlewares dependencies in `:expects`/`:requires`.
                             (update :handles #(into #{m} (keys %)))
                             ;; (update :handles #(into #{m} (keys %)))
                             (assoc :implemented-by m))))))
          middlewares)))

(defn- topologically-sort
  "Topologically sorts the given middleware descriptors according to
  expects/requires rules with the added heuristic that any middlewares that have
  no dependencies will be sorted toward the end."
  [stack]
  (let [stack (vec stack)
        ;; using indexes into the above vector as the vertices in the
        ;; graph algorithm, will translate back into middlewares at
        ;; the end.
        vertices (range (count stack))
        edges (vec (for [i1 vertices
                         i2 (range i1)
                         :let [x (comparator (stack i1) (stack i2))]
                         :when (not= 0 x)]
                     (if (neg? x) [i1 i2] [i2 i1])))
        connected (into #{} cat edges)
        ;; the trivial vertices have no connections, and we pull them
        ;; out here so we can make sure they get put on the end
        trivial-vertices (remove connected vertices)]
    (loop [sorted-vertices []
           remaining-edges edges
           remaining-vertices (filterv connected vertices)]
      (if (seq remaining-vertices)
        (let [non-initials (into #{} (map #(nth % 1)) remaining-edges)
              next-vertex (reduce (fn [_ v]
                                    (when-not (non-initials v)
                                      (reduced v)))
                                  nil remaining-vertices)]
          (if next-vertex
            (recur (conj sorted-vertices next-vertex)
                   (into [] (remove #(or (= next-vertex (nth % 0))
                                         (= next-vertex (nth % 1)))
                                    remaining-edges))
                   (into [] (remove #(= % next-vertex)) remaining-vertices))
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
        (mapv stack (into sorted-vertices trivial-vertices))))))

(defn linearize-middleware-stack
  "Given a list of middleware vars, sort them topologically, so that the
  middleware that depend on others come FIRST and the ones depended on come
  LAST. The returned
  order lends itself to being composed as-is into the final handler."
  [middleware-vars]
  (->> (extended-descriptors middleware-vars)
       topologically-sort
       (map :implemented-by)))
