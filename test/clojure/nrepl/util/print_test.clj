(ns nrepl.util.print-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing]]
   [nrepl.util.print :as print])
  (:import
   (java.io StringWriter)))

(defn- printed
  "What `print-fn` writes for `value`, given `options` if there are any."
  ([print-fn value]
   (let [writer (StringWriter.)]
     (print-fn value writer)
     (str writer)))
  ([print-fn value options]
   (let [writer (StringWriter.)]
     (print-fn value writer options)
     (str writer))))

(deftest pr-prints-like-clojure
  (are [value] (= (pr-str value) (printed print/pr value))
    nil
    "a string"
    [1 2 3]
    {:a/b 1}
    (range 5)))

(deftest pr-options-bind-the-print-vars
  (are [options value expected] (= expected (printed print/pr value options))
    {:print-length 2}              [1 2 3]         "[1 2 ...]"
    {:print-level 1}               [[1]]           "[#]"
    {:print-meta true}             ^{:a 1} []      "^{:a 1} []"
    {:print-readably false}        "a"             "a"
    {:print-namespace-maps true}   {:a/b 1}        "#:a{:b 1}"
    {:print-namespace-maps false}  {:a/b 1}        "{:a/b 1}"
    {:print-dup true}              {:a 1}          "#=(clojure.lang.PersistentArrayMap/create {:a 1})")
  (testing "several options at once"
    (is (= "[[#] [#] ...]"
           (printed print/pr [[[1]] [[2]] [[3]]] {:print-length 2 :print-level 2}))))
  (testing "options that aren't print vars are ignored"
    (is (= "[1 2 3]" (printed print/pr [1 2 3] {:right-margin 2 :no-such-option true}))))
  (testing "the bindings don't outlive the call"
    (printed print/pr [1 2 3] {:print-length 1})
    (is (nil? *print-length*))))

(deftest pprint-passes-options-to-clojure-pprint
  (is (= "{:a 1}" (printed print/pprint {:a 1})))
  (is (= "[1 2 ...]" (printed print/pprint [1 2 3] {:length 2})))
  (is (= ["[0" " 1" " 2" " 3" " 4]"]
         (str/split-lines (printed print/pprint (vec (range 5)) {:right-margin 3})))))
