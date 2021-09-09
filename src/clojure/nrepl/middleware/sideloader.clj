(ns nrepl.middleware.sideloader
  "Support the ability to interactively load resources (including Clojure source
  files) and classes from the client. This can be used to add dependencies to
  the nREPL environment after initial startup."
  {:author "Christophe Grand"
   :added  "0.7"}
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nrepl.middleware :as middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]))

;; TODO: dedup with base64 in elisions branch once both are merged
(defn base64-encode [^java.io.InputStream in]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        sb (StringBuilder.)]
    (loop [shift 4 buf 0]
      (let [got (.read in)]
        (if (neg? got)
          (do
            (when-not (= shift 4)
              (let [n (bit-and (bit-shift-right buf 6) 63)]
                (.append sb (.charAt table n))))
            (cond
              (= shift 2) (.append sb "==")
              (= shift 0) (.append sb \=))
            (str sb))
          (let [buf (bit-or buf (bit-shift-left got shift))
                n (bit-and (bit-shift-right buf 6) 63)]
            (.append sb (.charAt table n))
            (let [shift (- shift 2)]
              (if (neg? shift)
                (do
                  (.append sb (.charAt table (bit-and buf 63)))
                  (recur 4 0))
                (recur shift (bit-shift-left buf 6))))))))))

(defn base64-decode [^String s]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        in (java.io.StringReader. s)
        bos (java.io.ByteArrayOutputStream.)]
    (loop [bits 0 buf 0]
      (let [got (.read in)]
        (when-not (or (neg? got) (= 61 got))
          (let [table-idx (.indexOf table got)]
            (if (= -1 table-idx)
              (recur bits buf)
              (let [buf (bit-or table-idx (bit-shift-left buf 6))
                    bits (+ bits 6)]
                (if (<= 8 bits)
                  (let [bits (- bits 8)]
                    (.write bos (bit-shift-right buf bits))
                    (recur bits (bit-and 63 buf)))
                  (recur bits buf))))))))
    (.toByteArray bos)))

(defn- sideloader
  "Creates a classloader that obey standard delegating policy."
  [{:keys [transport] :as msg} loaded prefixes]
  (let [resolve-fn
        (fn [type name]
          (when-not (and prefixes (not (some #(str/starts-with? name %) prefixes)))
            (if-let [p (get @loaded [(clojure.core/name type) name])]
              ;; reuse results
              @p
              (let [p (promise)]
                ;; Swap into the atom *before* sending the lookup request to ensure that the server
                ;; knows about the loaded request when the client sends the response.
                (swap! loaded assoc [(clojure.core/name type) name] p)
                (t/send transport (response-for msg
                                                {:status :sideloader-lookup
                                                 :type type
                                                 :name name}))
                @p))))
        new-cl (memoize
                (fn [parent]
                  (proxy [clojure.lang.DynamicClassLoader] [parent]
                    (findResource [name]
                      (when-some [uri (resolve-fn "resource" name)]
                        uri))
                    (findClass [name]
                      (if-let [klass (resolve-fn "class" name)]
                        klass
                        (throw (ClassNotFoundException. name)))))))]
    (fn []
      (new-cl (.getContextClassLoader (Thread/currentThread))))))

(defn wrap-sideloader
  "Middleware that enables the client to serve resources and classes to the server."
  [h]
  (fn [{:keys [op type name content transport session prefixes] :as msg}]
    (case op
      "sideloader-start"
      (let [loaded (atom {})]
        (alter-meta! session assoc
                     :classloader (sideloader msg loaded prefixes)
                     ::loaded loaded))

      "sideloader-stop"
      (alter-meta! session dissoc :classloader ::loaded)

      "sideloader-provide"
      (let [loaded (::loaded (meta session))]
        (if-some [p (and loaded (@loaded [type name]))]
          (do
            (deliver
             p
             (let [bytes (base64-decode content)]
               (when (pos? (count bytes))
                 (case type
                   "resource"
                   (let [file (doto (java.io.File/createTempFile "nrepl-sideload-" (str "-" (re-find #"[^/]*$" name)))
                                .deleteOnExit)]
                     (io/copy bytes file)
                     (-> file .toURI .toURL))
                   "class"
                   (.defineClass ^java.lang.ClassLoader (.getContextClassLoader (Thread/currentThread)) name bytes nil)))))
            (t/send transport (response-for msg {:status :done})))
          (t/send transport (response-for msg {:status #{:done :unexpected-provide}
                                               :type type
                                               :name name}))))

      (h msg))))

(set-descriptor! #'wrap-sideloader
                 {:requires #{"clone"}
                  :expects #{"eval"}
                  :handles {"sideloader-start"
                            {:doc "Starts a sideloading session."
                             :requires {"session" "the id of the session"}
                             :optional {}
                             :returns {"status" "\"sideloader-lookup\", never ever returns \"done\"."}}
                            "sideloader-provide"
                            {:doc "Provides a requested class or resource."
                             :requires {"session" "the id of the session"
                                        "content" "base64 string"
                                        "type" "\"class\" or \"resource\""
                                        "name" "the class or resource name"}
                             :optional {}
                             :returns {}}}})
