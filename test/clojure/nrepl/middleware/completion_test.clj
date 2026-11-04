(ns nrepl.middleware.completion-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.core :as nrepl :refer [code]]
   [nrepl.test-helpers :refer :all]))

(defn dummy-completion [prefix _ns _options]
  [{:candidate prefix}])

(def-repl-test completions-op
  (is+ {:status #{:done}
        :completions not-empty}
       (message! {:op "completions" :prefix "map" :ns "clojure.core"})))

(def-repl-test completions-op-error
  (is+ {:status #{:done :error :completions-error}
        :completions mc/absent}
       (message! session {:op "completions"})))

(def-repl-test completions-op-unresolvable-custom-fn
  ;; An unresolvable :complete-fn falls back to the default rather than failing
  ;; the request, whether its namespace is missing or it isn't qualified.
  (doseq [bad ["my.missing.ns/complete" "unqualified"]]
    (is+ {:status #{:done}, :completions (mc/embeds [{:candidate "map"}])}
         (message! session {:op "completions" :prefix "map" :ns "clojure.core" :complete-fn bad}))))

(def-repl-test completions-op-custom-fn
  (is+ {:status #{:done}, :completions [{:candidate "map"}]}
       (message! session {:op "completions" :prefix "map" :ns "clojure.core" :complete-fn "nrepl.middleware.completion-test/dummy-completion"}))

  (testing "setting custom fn via dynvar"
    (repl-eval! session (code (do (set! nrepl.middleware.completion/*complete-fn*
                                        nrepl.middleware.completion-test/dummy-completion)
                                  nil)))
    (is+ {:status #{:done}, :completions [{:candidate "map"}]}
         (message! session {:op "completions" :prefix "map" :ns "clojure.core"}))))
