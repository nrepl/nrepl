(ns nrepl.middleware.caught-test
  {:author "Oleksandr Yakushev"}
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl :refer [code]]
   [nrepl.test-helpers :as th :refer :all]))

(defn custom-repl-caught
  [^Throwable t]
  (binding [*out* *err*]
    (println "foo" (type t))))

(def-repl-test caught-options
  (testing "bad symbol should fall back to default"
    (is+ [{:nrepl.middleware.caught/error "Couldn't resolve var my.missing.ns/repl-caught"
           :status #{:nrepl.middleware.caught/error}}
          {:err #"IllegalArgumentException"}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (first 1))
                   :nrepl.middleware.caught/caught "my.missing.ns/repl-caught"})))

  (testing "custom symbol should be used"
    (is+ [{:err #"foo java.lang.IllegalArgumentException"}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (first 1))
                   :nrepl.middleware.caught/caught `custom-repl-caught})))

  (testing "::print? option"
    (is+ [{:err #"IllegalArgumentException"}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"
           :nrepl.middleware.caught/throwable #"IllegalArgumentException"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (first 1))
                   :nrepl.middleware.caught/print? 1}))

    (is+ [{:err #"foo java.lang.IllegalArgumentException"}
          {:status #{:eval-error}
           :ex "class java.lang.IllegalArgumentException"
           :root-ex "class java.lang.IllegalArgumentException"
           :nrepl.middleware.caught/throwable #"IllegalArgumentException"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (first 1))
                   :nrepl.middleware.caught/caught `custom-repl-caught
                   :nrepl.middleware.caught/print? 1}))))

(defn custom-repl-caught-bar
  [^Throwable t]
  (binding [*out* *err*]
    (println "bar" (type t))))

(def-repl-test session-caught-options
  (testing "setting *caught-fn* works"
    (repl-eval! session (code (set! nrepl.middleware.caught/*caught-fn* (resolve `custom-repl-caught-bar))))
    (is+ [{:err #"bar java.lang.ArithmeticException"}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (/ 1 0))})))

  (testing "request can still override *caught-fn*"
    (is+ [{:err #"foo java.lang.ArithmeticException"}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (/ 1 0))
                   :nrepl.middleware.caught/caught `custom-repl-caught})))

  (testing "request can still provide ::print? option"
    (is+ [{:err #"bar java.lang.ArithmeticException"}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"
           :nrepl.middleware.caught/throwable #"Divide by zero"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (/ 1 0))
                   :nrepl.middleware.caught/print? 1}))

    (is+ [{:err #"foo java.lang.ArithmeticException"}
          {:status #{:eval-error}
           :ex "class java.lang.ArithmeticException"
           :root-ex "class java.lang.ArithmeticException"
           :nrepl.middleware.caught/throwable #"Divide by zero"}
          {:status #{:done}}]
         (message {:op "eval"
                   :code (code (/ 1 0))
                   :nrepl.middleware.caught/caught `custom-repl-caught
                   :nrepl.middleware.caught/print? 1}))))
