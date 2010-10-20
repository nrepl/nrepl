(ns #^{:doc ""
       :author "Chas Emerick"}
  clojure.tools.nrepl.helpers-test
  (:use clojure.test)
  (:require
    [clojure.tools.nrepl.helpers :as helpers]))

(deftest escape-and-string-argument
  (are [string escaped] (= escaped (helpers/escape string))
    "a" "a"
    "\"a" "\\\"a")
  (are [string arg] (= arg (helpers/string-argument string))
    "a" "\"a\""
    "\"a" "\"\\\"a\""))

