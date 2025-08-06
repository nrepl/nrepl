(ns nrepl.middleware.session-test
  {:author "Oleksandr Yakushev"}
  (:require
   [clojure.test :refer :all]
   [nrepl.config :as config]
   [nrepl.core-test :refer [repl-server-fixture with-repl-server]]))

(use-fixtures :each repl-server-fixture)

(def ^:dynamic *test-var1*)
(def ^:dynamic *test-var2* :root)

(deftest dynvar-defaults-test
  (testing "dynvar receives a default value from config"
    (with-redefs [config/config {:dynamic-vars {'nrepl.middleware.session-test/*test-var1* 1}}]
      (with-repl-server
        (let [result (repl-values session "nrepl.middleware.session-test/*test-var1*")]
          (is (= [1] result))))))

  (testing "config can override a dynvar that has a root value"
    (with-repl-server
      (let [result (repl-values session "nrepl.middleware.session-test/*test-var2*")]
        (is (= [:root] result))))

    (with-redefs [config/config {:dynamic-vars {'nrepl.middleware.session-test/*test-var2* :override}}]
      (with-repl-server
        (let [result (repl-values session "nrepl.middleware.session-test/*test-var2*")]
          (is (= [:override] result)))))))
