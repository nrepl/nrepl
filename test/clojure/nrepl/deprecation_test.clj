(ns nrepl.deprecation-test
  "Deprecations declared in middleware descriptors, and how requests that run
  into them are flagged."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]
   [matcher-combinators.matchers :as mc]
   [nrepl.middleware :as middleware]
   [nrepl.misc :as misc]
   [nrepl.server :as server]
   [nrepl.test-helpers :refer [is+ with-every-transport with-nrepl-client
                               with-nrepl-server]]
   [nrepl.transport :as t]))

(defn wrap-greetings
  "Handles a deprecated op, and one with a deprecated parameter."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (#{"hello" "greet"} op)
      (do (t/respond-to msg :greeting "Hello!")
          (t/respond-to msg :status :done))
      (handler msg))))

(middleware/set-descriptor!
 #'wrap-greetings
 {:requires #{}
  :expects #{}
  :handles {"hello" {:doc "Says hello."
                     :requires {}
                     :optional {}
                     :returns {"greeting" "The greeting."}
                     :deprecated {:since "1.9.0" :use "greet"}}
            "greet" {:doc "Greets."
                     :requires {}
                     :optional {"name" "Who to greet."
                                "who" "Who to greet."}
                     :returns {"greeting" "The greeting."
                               "text" "The greeting."}
                     :deprecated-params {"who" {:since "1.9.0"
                                                :use "name"
                                                :note "It goes away in 2.0."}}
                     :deprecated-returns {"text" {:use "greeting"}}}}})

(def ^:private warnings
  "What the server under test has logged."
  (atom []))

(defmacro ^:private with-greetings-server
  "Runs `body` against a server that has `wrap-greetings`, over every
  transport, collecting what it logs in `warnings`."
  [& body]
  `(with-every-transport
     (reset! @#'middleware/logged-deprecations #{})
     (reset! warnings [])
     (with-redefs [misc/log (fn [& args#] (swap! warnings conj (last args#)))]
       (with-nrepl-server {:handler (server/default-handler #'wrap-greetings)}
         (with-nrepl-client
           ~@body)))))

(deftest deprecated-op
  (with-greetings-server
    (testing "the response that completes the request says so"
      (is+ [{:greeting "Hello!", :nrepl/deprecations mc/absent}
            {:status #{:done}
             :nrepl/deprecations [{:kind "op", :name "hello", :since "1.9.0", :use "greet"}]}]
           (message {:op "hello"})))
    (testing "and it's logged, the first time only"
      (doall (message {:op "hello"}))
      (is (= ["Op hello is deprecated since 1.9.0. Use greet instead."] @warnings)))))

(deftest deprecated-param
  (with-greetings-server
    (testing "an op isn't flagged for a deprecated parameter the request doesn't use"
      (is+ {:status #{:done}, :nrepl/deprecations mc/absent}
           (message! {:op "greet" :name "Rich"}))
      (is (empty? @warnings)))
    (testing "but is when the request does"
      (is+ {:status #{:done}
            :nrepl/deprecations [{:kind "param", :name "who", :since "1.9.0", :use "name"
                                  :note "It goes away in 2.0."}]}
           (message! {:op "greet" :who "Rich"}))
      (is (= ["Parameter who of op greet is deprecated since 1.9.0. Use name instead. It goes away in 2.0."]
             @warnings)))))

(deftest ops-with-nothing-deprecated
  (with-greetings-server
    (is+ {:value ["3"], :status #{:done}, :nrepl/deprecations mc/absent}
         (message! {:op "eval" :code "(+ 1 2)"}))
    (is (empty? @warnings))))

(deftest describe-lists-deprecations
  (with-greetings-server
    (is+ {:ops {:hello {:deprecated {:since "1.9.0", :use "greet"}}
                :greet {:deprecated mc/absent
                        :deprecated-params {:who {:since "1.9.0", :use "name"}}
                        :deprecated-returns {:text {:use "greeting"}}}
                :eval {:deprecated mc/absent}}}
         ;; The keys of the descriptors are strings over EDN.
         (update (message! {:op "describe" :verbose? 1}) :ops walk/keywordize-keys))))

(deftest handler-is-left-alone-when-nothing-is-deprecated
  (let [handler (fn [_msg])]
    (is (identical? handler (middleware/wrap-deprecations handler server/default-middleware)))))

(deftest deprecated-middleware
  (let [logged (atom [])
        middleware (with-meta {:name "old"}
                     {::middleware/descriptor {:requires #{} :expects #{} :handles {}
                                               :deprecated {:since "1.9.0" :use "something else"}}})]
    (with-redefs [misc/log (fn [& args] (swap! logged conj (last args)))]
      (middleware/linearize-middleware-stack [middleware]))
    (is+ [#"^Middleware .* is deprecated since 1.9.0. Use something else instead.$"] @logged)))
