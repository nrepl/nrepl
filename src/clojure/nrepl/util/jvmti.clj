(ns nrepl.util.jvmti
  "This namespace contains code exclusive to JDK9+ and should not be attempted to
  load with earlier JDKs."
  (:require
   [clojure.java.io :as io])
  (:import
   (com.sun.tools.attach VirtualMachine)
   (java.lang ProcessHandle)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (nrepl JvmtiAgent)))

;;; Agent unpacking

(defonce ^:private temp-directory
  (.toFile (Files/createTempDirectory "nrepl" (into-array FileAttribute []))))

(defn- unpack-from-jar [resource-name]
  (let [path (io/file temp-directory resource-name)]
    (if-let [resource (io/resource resource-name)]
      (io/copy (io/input-stream resource) path)
      (throw (ex-info (str "Could not find " resource-name " in resources.") {})))
    (.getAbsolutePath path)))

(defn- macos? []
  (re-find #"(?i)mac" (System/getProperty "os.name")))

(defn- aarch64? []
  (re-find #"(?i)aarch64" (System/getProperty "os.arch")))

(def ^:private libnrepl-path
  (delay
    (let [lib (cond (macos?)   "libnrepl-macos-universal.so"
                    (aarch64?) "libnrepl-linux-arm64.so"
                    :else      "libnrepl-linux-x64.so")]
      (unpack-from-jar lib))))

;;; Agent loading

(defn- attach-self ^VirtualMachine []
  (VirtualMachine/attach (str (.pid (ProcessHandle/current)))))

(defn- load-libnrepl-agent []
  (.loadAgentPath (attach-self) @libnrepl-path))

(def ^:private agent-loaded (delay (load-libnrepl-agent)))

(defn stop-thread
  "Stop the given `thread` using JVMTI StopThread function. Risks state
  corruption. Should not be used prior to JDK20."
  [thread]
  @agent-loaded
  (JvmtiAgent/stopThread thread))
