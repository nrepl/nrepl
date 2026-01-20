(ns nrepl.middleware-test
  (:require
   [clojure.test :refer [deftest is are]]
   [matcher-combinators.matchers :as mc]
   [nrepl.middleware :as middleware :refer [linearize-middleware-stack]]
   nrepl.middleware.interruptible-eval
   [nrepl.server :refer [default-middleware]]
   [nrepl.test-helpers :refer [is+]]))

(defn- wonky-resolve [s] (if (symbol? s) (resolve s) s))

(defn- indexed-stack
  [x]
  (->> x
       (map wonky-resolve)
       shuffle
       linearize-middleware-stack
       (map-indexed #(vector (if (var? %2)
                               (-> (#'middleware/var-name %2) symbol name symbol)
                               %2)
                             %))
       (into {})))

(deftest sanity
  (let [stack (indexed-stack default-middleware)]
    (is (stack 'wrap-print))
    (are [before after] (< (stack before) (stack after))
      'interruptible-eval 'wrap-load-file
      'interruptible-eval 'session
      'interruptible-eval 'wrap-print))

  (let [n ^{::middleware/descriptor
            {:expects #{"clone"} :requires #{}}} {:dummy :middleware2}
        m ^{::middleware/descriptor
            {:expects #{"eval"} :requires #{n #'nrepl.middleware.print/wrap-print}}}
        {:dummy :middleware}
        q ^{::middleware/descriptor
            {:expects #{} :requires #{"describe" "eval"}}} {:dummy :middleware3}
        stack (indexed-stack (concat default-middleware [m q n]))]
    ;(->> stack clojure.set/map-invert (into (sorted-map)) vals println)
    (are [before after] (< (stack before) (stack after))
      'interruptible-eval m
      m 'wrap-print
      'session n
      m n

      'interruptible-eval 'wrap-load-file
      'interruptible-eval 'session
      'interruptible-eval 'wrap-print)))

(deftest append-dependency-free-middleware
  (let [m ^{::middleware/descriptor
            {:expects #{} :requires #{}}} {:dummy :middleware}
        n {:dummy "This not-middleware is supposed to be sans-descriptor, don't panic!"}
        stack (->> (concat default-middleware [m n])
                   shuffle
                   linearize-middleware-stack)]
    (is (= #{n m} (set (take-last 2 stack))))))

(deftest no-descriptor-warning
  (is+ #"No nREPL middleware descriptor in metadata of \{:dummy :middleware\}"
       (with-out-str
         (binding [*err* *out*]
           (indexed-stack (conj default-middleware {:dummy :middleware}))))))

(deftest missing-middleware-fail
  (is+ '{interruptible-eval 0, wrap-caught 1, wrap-print 2}
       (indexed-stack [#'nrepl.middleware.interruptible-eval/interruptible-eval]))
  (is+ #"Middleware #'nrepl.middleware.(caught|print)/wrap-(caught|print) is required by #'nrepl.middleware.interruptible-eval/interruptible-eval but is not present in middleware list."
       (with-out-str
         (binding [*err* *out*]
           (indexed-stack [#'nrepl.middleware.interruptible-eval/interruptible-eval])))))

(deftest middleware-deduplication
  (is+ [#'nrepl.middleware.print/wrap-print]
       (linearize-middleware-stack [#'nrepl.middleware.print/wrap-print
                                    #'nrepl.middleware.print/wrap-print
                                    #'nrepl.middleware.print/wrap-print])))

(deftest NREPL-53-regression
  (is (= [0 1 2]
         (map :id
              (linearize-middleware-stack
               [^{::middleware/descriptor
                  {:expects #{} :requires #{"1"}}}
                {:id 0}

                ^{::middleware/descriptor
                  {:expects #{} :requires #{} :handles {"1" {}}}}
                {:id 1}

                ^{::middleware/descriptor
                  {:expects #{"1"} :requires #{}}}
                {:id 2}])))))
