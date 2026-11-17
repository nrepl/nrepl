(ns nrepl.version-test
  (:require
   [clojure.test :refer [are deftest]]
   [nrepl.version :as version]))

(deftest parse-version
  (are [s parsed] (= (assoc parsed :version-string s) (#'version/parse-version s))
    "1.8.0"          {:major 1 :minor 8 :incremental 0 :qualifier nil}
    "1.8.1-SNAPSHOT" {:major 1 :minor 8 :incremental 1 :qualifier "SNAPSHOT"}
    "1.3.0-beta3"    {:major 1 :minor 3 :incremental 0 :qualifier "beta3"}
    ""               {:major nil :minor nil :incremental nil :qualifier nil}))
