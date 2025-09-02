(ns nrepl.middleware.completion-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.core :as nrepl :refer [code]]
   [nrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir clean-response]]
   [nrepl.test-helpers :refer [is+]])
  (:import
   (java.io File)))

(use-fixtures :each repl-server-fixture)

(defn dummy-completion [prefix _ns _options]
  [{:candidate prefix}])

(def-repl-test completions-op
  (is+ {:status #{:done}
        :completions not-empty}
       (-> (nrepl/message session {:op "completions" :prefix "map" :ns "clojure.core"})
           nrepl/combine-responses
           clean-response)))

(def-repl-test completions-op-error
  (is+ {:status #{:done :completion-error}
        :completions mc/absent}
       (-> (nrepl/message session {:op "completions"})
           nrepl/combine-responses
           clean-response)))

(def-repl-test completions-op-custom-fn
  (is+ {:status #{:done}, :completions [{:candidate "map"}]}
       (-> (nrepl/message session {:op "completions" :prefix "map" :ns "clojure.core" :complete-fn "nrepl.middleware.completion-test/dummy-completion"})
           nrepl/combine-responses
           clean-response))

  (testing "setting custom fn via dynvar"
    (repl-values session (code (do (set! nrepl.middleware.completion/*complete-fn*
                                         nrepl.middleware.completion-test/dummy-completion)
                                   nil)))
    (is+ {:status #{:done}, :completions [{:candidate "map"}]}
         (-> (nrepl/message session {:op "completions" :prefix "map" :ns "clojure.core"})
             nrepl/combine-responses
             clean-response))))
