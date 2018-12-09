(defproject nrepl "0.6.0-SNAPSHOT"
  :description "nREPL is a Clojure *n*etwork REPL."
  :url "https://nrepl.xyz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/nrepl"}
  :min-lein-version "2.6.1"
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :test-paths ["test/clojure"]
  :javac-options ["-target" "8" "-source" "8"]

  :dependencies [[nrepl/bencode "1.0.0"]]

  :aliases {"bump-version" ["change" "version" "leiningen.release/bump-version"]
            "test-all" ["with-profile" "+1.7:+1.8:+1.9:+fastlane" "test"]
            "docs" ["with-profile" "+maint" "run" "-m" "nrepl.impl.docs"]}

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

  :profiles {:fastlane {:dependencies [[nrepl/fastlane "0.1.0-SNAPSHOT"]]}
             ;; Clojure versions matrix
             :provided {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :master {:repositories [["snapshots"
                                      "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.10.0-master-SNAPSHOT"]]}

             :sysutils {:plugins [[lein-sysutils "0.2.0"]]}
             :maint {:source-paths ["src/maint"]}

             ;; CI tools
             :cloverage {:plugins [[lein-cloverage "1.0.13"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.6.1"]]
                      :cljfmt {:indents {as-> [[:inner 0]]
                                         with-debug-bindings [[:inner 0]]
                                         merge-meta [[:inner 0]]}}}

             :eastwood {:plugins [[jonase/eastwood "0.2.6"]]
                        :eastwood {:config-files ["eastwood.clj"]}}})
