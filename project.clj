(def dev-test-common-profile
  {:dependencies '[[com.github.ivarref/locksmith "0.1.9"]
                   [com.hypirion/io "0.3.1"]
                   [commons-net/commons-net "3.10.0"]
                   [lambdaisland/kaocha "1.89.1380"]
                   [lambdaisland/kaocha-junit-xml "1.17.101"]
                   [org.clojure/test.check "1.1.1"]
                   [nubank/matcher-combinators "3.9.1"
                    :exclusions [org.clojure/clojure]]]
   :resource-paths ["test"]
   :java-source-paths ["test/java"]})

(defproject nrepl (or (not-empty (System/getenv "PROJECT_VERSION"))
                      "0.0.0")
  :description "nREPL is a Clojure *n*etwork REPL."
  :url "https://nrepl.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/nrepl"}
  :min-lein-version "2.9.1"
  :source-paths ["src/clojure"]
  :resource-paths ["res"]
  :java-source-paths ["src/java"]
  :jvm-opts ["-Djdk.attach.allowAttachSelf"]
  :test-paths ["test/clojure"]
  :javac-options ["-target" "8" "-source" "8"]

  :aliases {"bump-version" ["change" "version" "leiningen.release/bump-version"]
            "docs" ["with-profile" "+docs" "run" "-m" "nrepl.impl.docs" "--file"
                    ~(clojure.java.io/as-relative-path
                      (clojure.java.io/file "doc" "modules" "ROOT" "pages" "ops.adoc"))]
            "kaocha" ["with-profile" "+test" "run" "-m" "kaocha.runner"]}

  :release-tasks [["vcs" "assert-committed"]
                  ["bump-version" "release"]
                  ["vcs" "commit" "Release %s"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["bump-version"]
                  ["vcs" "commit" "Begin %s"]]

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:dev ~dev-test-common-profile
             :test ~(merge
                     dev-test-common-profile
                     {:plugins      '[[test2junit "1.4.2"]]
                      :test2junit-output-dir "test-results"
                      ;; This skips any tests that doesn't work on all java versions
                      ;; TODO: replicate koacha's version filter logic here
                      :test-selectors {:default '(complement :min-java-version)}
                      :aliases {"test" "test2junit"}})
             :junixsocket {:dependencies [[com.kohlschutter.junixsocket/junixsocket-core "2.10.1" :extension "pom"]]}
             :clj-kondo {:dependencies [[clj-kondo "2026.01.19"]]}
             ;; Clojure versions matrix
             :provided {:dependencies [[org.clojure/clojure "1.12.4"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]
                    :source-paths ["src/spec"]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]]
                    :source-paths ["src/spec"]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.4"]]
                    :source-paths ["src/spec"]}



             ;;; Docs generation profile
             ;;
             ;; It contains the small CLI utility (aliased to "lein docs") that
             ;; generates the nREPL ops documentation from their descriptor
             ;; metadata.
             :docs {:source-paths ["src/maint"]
                    :dependencies [[org.clojure/tools.cli "1.2.245"]]}

             ;; CI tools
             :cloverage {:plugins [[lein-cloverage "1.2.4"]]
                         :dependencies [[cloverage "1.2.4"]]
                         :cloverage {:codecov? true
                                     ;; Cloverage can't handle some of the code
                                     ;; in this project
                                     :test-ns-regex [#"^((?!nrepl.sanity-test).)*$"]}}

             :cljfmt {:plugins [[dev.weavejester/lein-cljfmt "0.15.6"]]
                      :cljfmt {:extra-indents {run-with [[:inner 0]]
                                               testing-print [[:inner 0]]
                                               safe-handle [[:inner 0]]
                                               when-require [[:inner 0]]}}}

             :eastwood [:test
                        {:plugins [[jonase/eastwood "1.4.3"]]
                         :eastwood {:config-files ["eastwood.clj"]
                                    :ignored-faults {:unused-ret-vals {nrepl.util.completion-test true
                                                                       nrepl.bencode true}
                                                     :reflection {nrepl.socket.dynamic true}}}}]})
