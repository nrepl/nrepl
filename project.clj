(defproject nrepl/nrepl "0.3.0"
  :description "nREPL is a Clojure *n*etwork REPL."
  :min-lein-version "2.6.1"
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :test-paths ["test/clojure"]

  :dependencies [[org.clojure/tools.logging "0.4.1"]]

  :aliases {"bump-version" ["change" "version" "leiningen.release/bump-version"]}

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

  :profiles {
             ;; Clojure versions matrix
             :provided {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :master {:repositories [["snapshots"
                                      "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.10.0-master-SNAPSHOT"]]}

             :sysutils {:plugins [[lein-sysutils "0.2.0"]]}

             ;; CI tools
             :codox {:plugins [[lein-codox "0.10.3"]]
                     :codox #=(eval
                               (let [repo   (or (System/getenv "TRAVIS_REPO_SLUG") "nrepl/nREPL")
                                     branch (or (System/getenv "AUTODOC_SUBDIR") "master")
                                     urlfmt "https://github.com/%s/blob/%s/{filepath}#L{line}"]
                                 {;; Distinct docs for tagged releases as well as "master"
                                  :output-path (str "gh-pages/" branch)
                                  ;; Generate URI links from docs back to this branch in github
                                  :source-uri  (format urlfmt repo branch)}))}

             :cloverage {:plugins [[lein-cloverage "1.0.11-SNAPSHOT"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.5.7"]]
                      :cljfmt {:indents {as-> [[:inner 0]]
                                         with-debug-bindings [[:inner 0]]
                                         merge-meta [[:inner 0]]}}}

             :eastwood {:plugins [[jonase/eastwood "0.2.6"]]
                        :eastwood {:config-files ["eastwood.clj"]}}})
