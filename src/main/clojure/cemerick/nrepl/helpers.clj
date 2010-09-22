(ns cemerick.nrepl.helpers
  (:import (java.io StringReader)))

(defn escape
  [#^String s]
  (.replace s "\"" "\\\""))

(defn string-argument
  [s]
  (str \" (escape s) \"))

(defn load-with-debug-info
  "Load a code string using a source file path and name source-path for debug info
   (line numbers, etc)."
  [code file-path filename]
  (clojure.lang.Compiler/load
    (StringReader. code)
    file-path
    filename))