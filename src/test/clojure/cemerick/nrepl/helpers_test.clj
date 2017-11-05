(ns cemerick.nrepl.helpers-test
  (:use clojure.test)
  (:require
    [cemerick.nrepl.helpers :as helpers]))

(deftest escape-and-string-argument
  (are [string escaped] (= escaped (helpers/escape string))
    "a" "a"
    "\"a" "\\\"a")
  (are [string arg] (= arg (helpers/string-argument string))
    "a" "\"a\""
    "\"a" "\"\\\"a\""))

