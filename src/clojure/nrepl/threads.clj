(ns nrepl.threads)

(def ^{:dynamic true
       :tag java.util.concurrent.ThreadFactory} *platform-thread-factory* nil)
