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

;; oh, kill me now
(defn- markdown-escape
  [^String s]
  (.replaceAll s "([*_])" "\\\\$1"))

(defn- deprecation-text
  "What a descriptor's deprecation map says, as a sentence or three."
  [{:keys [since use note]}]
  (str "Deprecated"
       (when since (str " since " since))
       "."
       (when use (str " Use `" use "` instead."))
       (when note (str " " note))))

(defn- message-slot-markdown
  ([msg-slot-docs] (message-slot-markdown msg-slot-docs nil))
  ([msg-slot-docs deprecations]
   (str/join (for [[k v] msg-slot-docs]
               (format "* `%s` %s%s\n" (pr-str k) (markdown-escape v)
                       (if-let [deprecation (get deprecations k)]
                         (str " **" (deprecation-text deprecation) "**")
                         ""))))))

(defn- version-note
  "The optional nREPL version to mention in the generated header."
  [version]
  (when version
    (str " (nREPL v" version ")")))

(defn- describe-markdown
  "Given a message containing the response to a verbose :describe message,
generates a markdown string conveying the information therein, suitable for
use in e.g. wiki pages, github, etc."
  [{:keys [ops]} version]
  (apply str "# Supported nREPL operations\n\n"
         "<small>generated from a verbose 'describe' response"
         (version-note version)
         "</small>\n\n## Operations"
         (for [[op {:keys [doc optional requires returns
                           deprecated deprecated-params deprecated-returns]}] (sort ops)]
           (str "\n\n### `" (pr-str op) "`\n\n"
                (when deprecated
                  (str "**" (deprecation-text deprecated) "**\n\n"))
                (markdown-escape doc) "\n\n"
                "###### Required parameters\n\n"
                (message-slot-markdown (sort requires) deprecated-params)
                "\n\n###### Optional parameters\n\n"
                (message-slot-markdown (sort optional) deprecated-params)
                "\n\n###### Returns\n\n"
                (message-slot-markdown (sort returns) deprecated-returns)))))

;; because `` is expected to work, this only escapes certain characters. As opposed to using + to properly escape everything.
(defn- adoc-escape
  [s]
  (-> s
      (str/replace #"\*(.+?)\*" "\\\\*$1*")
      (str/replace #"\_(.+?)\_" "\\\\_$1_")
      (str/escape {\` "``"})))

(defn- message-slot-adoc
  ([msg-slot-docs] (message-slot-adoc msg-slot-docs nil))
  ([msg-slot-docs deprecations]
   (if (seq msg-slot-docs)
     (str/join (for [[k v] msg-slot-docs]
                 (format "* `%s` %s%s\n" (pr-str k) (adoc-escape v)
                         (if-let [deprecation (get deprecations k)]
                           (str " *" (adoc-escape (deprecation-text deprecation)) "*")
                           ""))))
     "{blank}")))

(defn- describe-adoc
  "Given a message containing the response to a verbose :describe message,
  generates a asciidoc string conveying the information therein, suitable for
  use in e.g. wiki pages, github, etc."
  [{:keys [ops]} version]
  (apply str "= Supported nREPL operations\n\n"
         "[small]#generated from a verbose 'describe' response"
         (version-note version)
         "#\n\n== Operations"
         (for [[op {:keys [doc optional requires returns
                           deprecated deprecated-params deprecated-returns]}] (sort ops)]
           (str "\n\n=== `" (name op) "`\n\n"
                (when deprecated
                  (str "WARNING: " (adoc-escape (deprecation-text deprecated)) "\n\n"))
                (adoc-escape doc) "\n\n"
                "Required parameters::\n"
                (message-slot-adoc (sort requires) deprecated-params)
                "\n\nOptional parameters::\n"
                (message-slot-adoc (sort optional) deprecated-params)
                "\n\nReturns::\n"
                (message-slot-adoc (sort returns) deprecated-returns)
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
