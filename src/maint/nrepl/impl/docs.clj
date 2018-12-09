(ns nrepl.impl.docs
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]
   [nrepl.transport :as transport]
   [nrepl.version :as version])
  (:import
   (java.io File)))

;; oh, kill me now
(defn- markdown-escape
  [^String s]
  (.replaceAll s "([*_])" "\\\\$1"))

(defn- message-slot-markdown
  [msg-slot-docs]
  (apply str (for [[k v] msg-slot-docs]
               (format "* `%s` %s\n" (pr-str k) (markdown-escape v)))))

(defn- describe-markdown
  "Given a message containing the response to a verbose :describe message,
generates a markdown string conveying the information therein, suitable for
use in e.g. wiki pages, github, etc."
  [{:keys [ops versions]}]
  (apply str "# Supported nREPL operations

<small>generated from a verbose 'describe' response (nREPL v"
         (:version-string version/version)
         ")</small>\n\n## Operations"
         (for [[op {:keys [doc optional requires returns]}] ops]
           (str "\n\n### `" (pr-str op) "`\n\n"
                (markdown-escape doc) "\n\n"
                "###### Required parameters\n\n"
                (message-slot-markdown requires)
                "\n\n###### Optional parameters\n\n"
                (message-slot-markdown optional)
                "\n\n###### Returns\n\n"
                (message-slot-markdown returns)))))

;; because `` is expected to work, this only escapes certain characters. As opposed to using + to properly escape everything.
(defn- adoc-escape
  [s]
  (-> s
      (string/replace #"\*(.+?)\*" "\\\\*$1*")
      (string/replace #"\_(.+?)\_" "\\\\_$1_")
      (string/escape {\` "``"})))

(defn- message-slot-adoc
  [msg-slot-docs]
  (if (seq msg-slot-docs)
    (apply str (for [[k v] msg-slot-docs]
                 (format "* `%s` %s\n" (pr-str k) (adoc-escape v))))
    "{blank}"))

(defn- describe-adoc
  "Given a message containing the response to a verbose :describe message,
  generates a asciidoc string conveying the information therein, suitable for
  use in e.g. wiki pages, github, etc."
  [{:keys [ops versions]}]
  (apply str "= Supported nREPL operations\n\n"
         "[small]#generated from a verbose 'describe' response (nREPL v"
         (:version-string version/version)
         ")#\n\n== Operations"
         (for [[op {:keys [doc optional requires returns]}] (sort ops)]
           (str "\n\n=== `" (pr-str op) "`\n\n"
                (adoc-escape doc) "\n\n"
                "Required parameters::\n"
                (message-slot-adoc (sort requires))
                "\n\nOptional parameters::\n"
                (message-slot-adoc (sort optional))
                "\n\nReturns::\n"
                (message-slot-adoc (sort returns))
                "\n"))))

(defn -main
  "Regenerate the ops documentation in ops.adoc"
  [& args]
  (let [project-base-dir (File. (System/getProperty "nrepl.basedir" "."))
        ops-adoc (io/file project-base-dir "doc" "modules" "ROOT" "pages" "ops.adoc")]
    (spit ops-adoc
          (str
           "////\n"
           "This file is _generated_ by " #'-main
           "\n   *Do not edit!*\n"
           "////\n"
           (let [[local remote] (transport/piped-transports)
                 handler (server/default-handler)
                 msg {:op "describe"
                      :verbose? "true"
                      :id "1"}]
             (handler (assoc msg :transport remote))
             (-> (nrepl/response-seq local 500)
                 first
                 clojure.walk/keywordize-keys
                 describe-adoc))))
    (println (str "Regenerated " (.getPath ops-adoc)))))
