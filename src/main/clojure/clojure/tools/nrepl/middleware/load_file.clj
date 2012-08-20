(ns ^{:author "Chas Emerick"}
     clojure.tools.nrepl.middleware.load-file
  (:require [clojure.tools.nrepl.middleware.interruptible-eval :as eval])
  (:use [clojure.tools.nrepl.middleware :as middleware :only (set-descriptor!)]))

(defn ^{:dynamic true} load-file-code
  "Given the contents of a file, its _source-path-relative_ path,
   and its filename, returns a string of code (or seq of expressions)
   that, when evaluated, will load those contents with appropriate
   filename references and line numbers in metadata, etc."
  [file file-path file-name]
  (apply format
    "(clojure.lang.Compiler/load (java.io.StringReader. %s) %s %s)"
    (map pr-str [file file-path file-name])))

(defn wrap-load-file
  "Middleware that evaluates a file's contents, as per load-file,
   but with all data supplied in the sent message (i.e. safe for use
   with remote REPL environments).

   This middleware depends on the availability of an :op \"eval\"
   middleware below it (such as interruptable-eval)."
  [h]
  (fn [{:keys [op file file-name file-path] :as msg}]
    (if (not= op "load-file")
      (h msg)
      (h (assoc msg
           :op "eval"
           :code (load-file-code file file-path file-name))))))

(set-descriptor! #'wrap-load-file
  {:requires #{}
   :expects #{"eval"}
   :handles {"load-file"
             {:doc "Loads a body of code, using supplied path and filename info to set source file and line number metadata. Delegates to underlying \"eval\" middleware/handler."
              :requires {"file" "Full contents of a file of code."}
              :optional {"file-path" "Source-path-relative path of the source file, e.g. clojure/java/io.clj"
                         "file-name" "Name of source file, e.g. io.clj"}
              :returns (-> (meta #'eval/interruptible-eval)
                         ::middleware/descriptor
                         :handles
                         (get "eval")
                         :returns)}}})