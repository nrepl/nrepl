(ns nrepl.test-helpers
  (:require [clojure.string :as str])
  (:import [java.io File]))

(def sys-newline
  "The system's newline character sequence."
  (System/lineSeparator))

(def win?
  "Whether we are running on MS-Windows."
  (.startsWith (System/getProperty "os.name") "Windows"))

(defn string=
  "Like `clojure.core/=` applied on STRING1 and STRING2, but treats
  all line endings as equal."
  [string1 string2]
  (= (str/split-lines string1) (str/split-lines string2)))

(defn newline->sys
  "Returns TEXT, converting `\n` line endings to those of the underlying
  system's, if different."
  [text]
  (if-not (= sys-newline "\n")
    (str/replace text "\n" sys-newline)
    text))

(def sys-file-sep
  "The character separating components in a path."
  File/separator)

(defn dir-sep->sys
  "Replace the `/` separator component used in tests with the one used
  by the system."
  [path]
  (if-not (= sys-file-sep "/")
    (str/replace path "/" sys-file-sep)
    path))
