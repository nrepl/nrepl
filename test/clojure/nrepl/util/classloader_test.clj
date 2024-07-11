(ns nrepl.util.classloader-test
  (:require [clojure.test :refer :all]
            [nrepl.util.classloader :as classloader])
  (:import clojure.lang.DynamicClassLoader))

(def top-cl (->> (.getContextClassLoader (Thread/currentThread))
                 (iterate #(.getParent ^ClassLoader %))
                 (take-while identity)
                 last))

(deftest find-topmost-dcl-test
  (let [dcl1 (DynamicClassLoader. top-cl)
        dcl2 (DynamicClassLoader. dcl1)]
    (is (= nil (classloader/find-topmost-dcl top-cl)))
    (is (= dcl1 (classloader/find-topmost-dcl dcl1)))
    (is (= dcl1 (classloader/find-topmost-dcl dcl2)))))

(deftest dynamic-classloader-test
  (let [dcl1 (DynamicClassLoader. top-cl)
        dcl2 (DynamicClassLoader. dcl1)]
    (is (instance? DynamicClassLoader (classloader/dynamic-classloader top-cl)))
    (is (= dcl1 (classloader/dynamic-classloader dcl1)))
    (is (= dcl1 (classloader/dynamic-classloader dcl2)))))
