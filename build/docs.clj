(ns build.docs
  "Doc generation utilities"
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]
   [nrepl.transport :as transport]))

(declare docs)

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

;; oh, kill me now
(defn- markdown-escape
  [^String s]
  (.replaceAll s "([*_])" "\\\\$1"))

(defn- message-slot-markdown
  [msg-slot-docs]
  (str/join (for [[k v] msg-slot-docs]
              (format "* `%s` %s\n" (pr-str k) (markdown-escape v)))))

(defn- describe-markdown
  "Given a message containing the response to a verbose :describe message,
generates a markdown string conveying the information therein, suitable for
use in e.g. wiki pages, github, etc."
  [{:keys [ops]} version]
  (apply str "# Supported nREPL operations

<small>generated from a verbose 'describe' response (nREPL v"
         version
         ")</small>\n\n## Operations"
         (for [[op {:keys [doc optional requires returns]}] (sort ops)]
           (str "\n\n### `" (pr-str op) "`\n\n"
                (markdown-escape doc) "\n\n"
                "###### Required parameters\n\n"
                (message-slot-markdown (sort requires))
                "\n\n###### Optional parameters\n\n"
                (message-slot-markdown (sort optional))
                "\n\n###### Returns\n\n"
                (message-slot-markdown (sort returns))))))

;; because `` is expected to work, this only escapes certain characters. As opposed to using + to properly escape everything.
(defn- adoc-escape
  [s]
  (-> s
      (str/replace #"\*(.+?)\*" "\\\\*$1*")
      (str/replace #"\_(.+?)\_" "\\\\_$1_")
      (str/escape {\` "``"})))

(defn- message-slot-adoc
  [msg-slot-docs]
  (if (seq msg-slot-docs)
    (str/join (for [[k v] msg-slot-docs]
                (format "* `%s` %s\n" (pr-str k) (adoc-escape v))))
    "{blank}"))

(defn- describe-adoc
  "Given a message containing the response to a verbose :describe message,
  generates a asciidoc string conveying the information therein, suitable for
  use in e.g. wiki pages, github, etc."
  [{:keys [ops]} version]
  (apply str "= Supported nREPL operations\n\n"
         "[small]#generated from a verbose 'describe' response (nREPL v"
         version
         ")#\n\n== Operations"
         (for [[op {:keys [doc optional requires returns]}] (sort ops)]
           (str "\n\n=== `" (name op) "`\n\n"
                (adoc-escape doc) "\n\n"
                "Required parameters::\n"
                (message-slot-adoc (sort requires))
                "\n\nOptional parameters::\n"
                (message-slot-adoc (sort optional))
                "\n\nReturns::\n"
                (message-slot-adoc (sort returns))
                "\n"))))

(defn- format-response [format resp version]
  (cond (= format "raw") (pr-str (select-keys resp [:ops]))
        (= format "md") (str "<!-- This file is *generated* by " #'docs
                             "\n   **Do not edit!** -->\n"
                             (describe-markdown resp version))
        (= format "adoc") (str "////\n"
                               "This file is _generated_ by " #'docs
                               "\n   *Do not edit!*\n"
                               "////\n"
                               (describe-adoc resp version))))

(defn generate-ops-info []
  (let [[local remote] (transport/piped-transports)
        handler (server/default-handler)
        msg {:op "describe"
             :verbose? "true"
             :id "1"}]
    (handler (assoc msg :transport remote))
    (-> (nrepl/response-seq local 500)
        first
        walk/keywordize-keys)))

(defn docs
  "Regenerate and output the ops documentation to the specified destination in the
  specified format."
  [{:keys [file format version]}]
  (let [file (some-> file io/file)
        format (or format "adoc")
        _ (assert (#{"raw" "adoc" "md"} format))
        docs (format-response format (generate-ops-info) version)]
    (if file
      (do (spit file docs)
          (println "Regenerated" (.getAbsolutePath file)))
      (println docs))))
