(ns nrepl.impl.docs
  "Doc generation utilities"
  (:require
   [clojure.tools.cli :as cli]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]
   [nrepl.transport :as transport]
   [nrepl.version :as version])
  (:import
   (java.io File)))

(declare -main)

(def cli-options
  [["-f" "--file FILE" "File to write output into (defaults to standard output)"
    :parse-fn #(File. %) :default *out*]
   ["-h" "--help" "Show this help message"]
   ["-o" "--output OUT" "One of: raw, adoc, md" :default "adoc"
    :validate [#(contains? #{"raw" "adoc" "md"} %)]]])

(defn- exit [status msg]
  (println msg)
  (System/exit status))

(defn- usage [options-summary]
  (->> [(:doc (meta #'-main))
        ""
        "Usage: lein -m +maint run nrepl.impl.docs [options]"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

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
           (str "\n\n=== `" (pr-str (name op)) "`\n\n"
                (adoc-escape doc) "\n\n"
                "Required parameters::\n"
                (message-slot-adoc (sort requires))
                "\n\nOptional parameters::\n"
                (message-slot-adoc (sort optional))
                "\n\nReturns::\n"
                (message-slot-adoc (sort returns))
                "\n"))))

(defn- format-response [format resp]
  (cond (= format "raw") (pr-str (select-keys resp [:ops]))
        (= format "md") (str "<!-- This file is *generated* by " #'-main
                             "\n   **Do not edit!** -->\n"
                             (describe-markdown resp))
        (= format "adoc") (str "////\n"
                               "This file is _generated_ by " #'-main
                               "\n   *Do not edit!*\n"
                               "////\n"
                               (describe-adoc resp))))

(defn generate-ops-info []
  (let [[local remote] (transport/piped-transports)
        handler (server/default-handler)
        msg {:op "describe"
             :verbose? "true"
             :id "1"}]
    (handler (assoc msg :transport remote))
    (-> (nrepl/response-seq local 500)
        first
        clojure.walk/keywordize-keys)))

(defn -main
  "Regenerate and output the ops documentation to the specified destination in the specified format."
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors))
      :else
      (let [file (:file options)
            format (:output options)
            resp (generate-ops-info)
            docs (format-response format resp)]
        (if (= *out* file) (println docs)
            (do (spit file docs)
                (println (str "Regenerated " (.getAbsolutePath file)))))))))
