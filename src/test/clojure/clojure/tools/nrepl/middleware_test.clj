(ns clojure.tools.nrepl.middleware-test
  (:require (clojure.tools.nrepl.middleware
              interruptible-eval
              load-file
              pr-values
              session))
  (:use [clojure.tools.nrepl.middleware :as middleware]
        clojure.test))

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
            {:expects #{} :requires #{"describe"}}} {:dummy :middleware3}
        stack (indexed-stack (concat default-middlewares [m q n]))]
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

(deftest no-descriptor-warning
  (is (.contains
        (with-out-str
          (binding [*err* *out*]
            (indexed-stack (conj default-middlewares {:dummy :middleware}))))
        "No nREPL middleware descriptor in metadata of {:dummy :middleware}")))