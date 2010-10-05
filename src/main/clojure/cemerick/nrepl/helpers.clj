(ns cemerick.nrepl.helpers
  (:import (java.io File StringReader)))

(defn escape
  [#^String s]
  (.replace s "\"" "\\\""))

(defn string-argument
  [s]
  (str \" (escape s) \"))