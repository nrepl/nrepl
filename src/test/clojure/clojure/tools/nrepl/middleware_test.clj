(ns clojure.tools.nrepl.middleware-test
  (:require [clojure.test :refer :all]
            [clojure.tools.nrepl.middleware :as middleware :refer [linearize-middleware-stack]]
            clojure.tools.nrepl.middleware.interruptible-eval
            clojure.tools.nrepl.middleware.load-file
            clojure.tools.nrepl.middleware.session))

; wanted to just use resolve to avoid the long var names, but
; it seems that unqualified resolves *don't work* within the context of a
; clojure-maven-plugin test execution?!?
(def ^{:private true} default-middlewares
  [#'clojure.tools.nrepl.middleware.session/add-stdin
   #'clojure.tools.nrepl.middleware.load-file/wrap-load-file
   #'clojure.tools.nrepl.middleware/wrap-describe
   #'clojure.tools.nrepl.middleware.session/session
   #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval])

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
  (let [stack (indexed-stack default-middlewares)]
    (is (stack 'pr-values))
    (are [before after] (< (stack before) (stack after))
         'interruptible-eval 'wrap-load-file
         'interruptible-eval 'session
         'wrap-describe 'pr-values
         'interruptible-eval 'pr-values))

  (let [n ^{::middleware/descriptor
            {:expects #{"clone"} :requires #{}}} {:dummy :middleware2}
        m ^{::middleware/descriptor
            {:expects #{"eval"} :requires #{n #'clojure.tools.nrepl.middleware.pr-values/pr-values}}}
           {:dummy :middleware}
        q ^{::middleware/descriptor
            {:expects #{} :requires #{"describe" "eval"}}} {:dummy :middleware3}
        stack (indexed-stack (concat default-middlewares [m q n]))]
    ;(->> stack clojure.set/map-invert (into (sorted-map)) vals println)
    (are [before after] (< (stack before) (stack after))
         'interruptible-eval m
         m 'pr-values
         'session n
         q 'wrap-describe
         m n

         'interruptible-eval 'wrap-load-file
         'interruptible-eval 'session
         'wrap-describe 'pr-values
         'interruptible-eval 'pr-values)))

(deftest append-dependency-free-middleware
  (let [m ^{::middleware/descriptor
            {:expects #{} :requires #{}}} {:dummy :middleware}
        n {:dummy "This not-middleware is supposed to be sans-descriptor, don't panic!"}
        stack (->> (concat default-middlewares [m n])
                shuffle
                linearize-middleware-stack)]
    (is (= #{n m} (set (take-last 2 stack))))))

(deftest no-descriptor-warning
  (is (.contains
        (with-out-str
          (binding [*err* *out*]
            (indexed-stack (conj default-middlewares {:dummy :middleware}))))
        "No nREPL middleware descriptor in metadata of {:dummy :middleware}")))

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
