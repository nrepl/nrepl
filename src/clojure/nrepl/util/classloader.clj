(ns nrepl.util.classloader
  "Creating and managing classloaders supplied to evaluated code."
  {:added "1.3"}
  (:import clojure.lang.DynamicClassLoader))

;; TODO : test
(defn find-topmost-dcl
  "Try to find and return the highest level DynamicClassLoader instance in the
  given classloader chain. If not a single DCL is found, return nil."
  ^DynamicClassLoader
  [^ClassLoader cl]
  (loop [cl cl, dcl nil]
    (if cl
      (recur (.getParent cl) (if (instance? DynamicClassLoader cl)
                               cl dcl))
      dcl)))

(defn dynamic-classloader
  "Return the topmost DynamicClassLoader if it is present among the parents of the
  given classloader, otherwise create a new DynamicClassLoader. `classloader`
  defaults to thread's context classloader if not provided."
  ^DynamicClassLoader
  ([]
   (dynamic-classloader (.getContextClassLoader (Thread/currentThread))))
  ([classloader]
   (or (find-topmost-dcl classloader)
       (DynamicClassLoader. classloader))))
