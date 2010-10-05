(ns cemerick.nrepl.tooling.load
  (:import (java.io File StringReader))
  (:require
    [cemerick.nrepl.helpers :as helpers]))

(defn load-with-debug-info
  "Load a code string using a source file path and name source-path for debug info
   (line numbers, etc)."
  [code file-path filename]
  (clojure.lang.Compiler/load
    (StringReader. code)
    file-path
    filename))

(defn load-file-command
  [#^File f source-paths]
  (apply format
    "(cemerick.nrepl.tooling.load/load-with-debug-info %s %s %s)"
    (map helpers/string-argument
      [(slurp f "UTF-8")
       (.toAbsolutePath f)
       (.getName f)])))