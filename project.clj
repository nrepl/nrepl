(defproject nrepl "1.3.0-beta2"
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
            "test-all" ["with-profile" "+1.7:+1.8:+1.9:+1.10:+1.11:+fastlane:+junixsocket" "test"]
            "docs" ["with-profile" "+maint" "run" "-m" "nrepl.impl.docs" "--file"
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

  :profiles {:fastlane {:dependencies [[nrepl/fastlane "0.1.0"]]}
             :test {:dependencies [[com.github.ivarref/locksmith "0.1.9"]
                                   [com.hypirion/io "0.3.1"]
                                   [commons-net/commons-net "3.10.0"]
                                   [lambdaisland/kaocha "1.89.1380"]
                                   [lambdaisland/kaocha-junit-xml "1.17.101"]
                                   [org.clojure/test.check "1.1.1"]]
                    :java-source-paths ["test/java"]
                    :plugins      [[test2junit "1.4.2"]]
                    :test2junit-output-dir "test-results"
                    ;; This skips any tests that doesn't work on all java versions
                    ;; TODO: replicate koacha's version filter logic here
                    :test-selectors {:default (complement :min-java-version)}
                    :aliases {"test" "test2junit"}}
             :junixsocket {:jvm-opts ["-Dnrepl.test.junixsocket=true"]
                           :dependencies [[com.kohlschutter.junixsocket/junixsocket-core "2.9.1" :extension "pom"]]}
             :clj-kondo {:dependencies [[clj-kondo "2024.03.13"]]}
             ;; Clojure versions matrix
             :provided {:dependencies [[org.clojure/clojure "1.11.3"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.2"]]
                    :source-paths ["src/spec"]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.3"]]
                    :source-paths ["src/spec"]}
             :master {:repositories [["snapshots"
                                      "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.12.0-master-SNAPSHOT"]]}

             ;;; Maintenance profile
             ;;
             ;; It contains the small CLI utility (aliased to "lein docs") that
             ;; generates the nREPL ops documentation from their descriptor
             ;; metadata.
             :maint {:source-paths ["src/maint"]
                     :dependencies [[org.clojure/tools.cli "1.1.230"]]}

             ;; CI tools
             :cloverage {:plugins [[lein-cloverage "1.2.4"]]
                         :dependencies [[cloverage "1.2.4"]]
                         :cloverage {:codecov? true
                                     ;; Cloverage can't handle some of the code
                                     ;; in this project
                                     :test-ns-regex [#"^((?!nrepl.sanity-test).)*$"]}}

             :cljfmt {:plugins [[lein-cljfmt "0.8.0"]]
                      :cljfmt {:indents {delay [[:inner 0]]
                                         returning [[:inner 0]]
                                         run-with [[:inner 0]]
                                         testing-dynamic [[:inner 0]]
                                         testing-print [[:inner 0]]}}}

             :eastwood [:test
                        {:plugins [[jonase/eastwood "1.4.0"]]
                         :eastwood {:config-files ["eastwood.clj"]
                                    :ignored-faults {:non-dynamic-earmuffs {nrepl.middleware.load-file true}
                                                     :unused-ret-vals {nrepl.util.completion-test true
                                                                       nrepl.bencode true}
                                                     :reflection {nrepl.socket.dynamic true}
                                                     :implicit-dependencies {nrepl.middleware.interruptible-eval true}}}}]})
