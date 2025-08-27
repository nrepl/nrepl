(ns nrepl.middleware.load-file
  {:author "Chas Emerick"}
  (:require
   [nrepl.middleware :as middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :as caught]
   [nrepl.middleware.interruptible-eval :as eval]
   [nrepl.middleware.print :as print])
  (:import clojure.lang.Compiler
           java.io.StringReader
           nrepl.transport.Transport))

;; Be vary of JVM method size limit (can't eval an expression larger than 64KB).
;; http://groups.google.com/group/clojure/browse_thread/thread/f54044da06b9939f

(defn ^{:dynamic true, :deprecated "1.4.0"}
  load-file-code
  "You should use the approach implemented in `load-file-eval-msg` instead.

   Given the contents of a file, its _source-path-relative_ path,
   and its filename, returns a string of code containing a single
   expression that, when evaluated, will load those contents with
   appropriate filename references and line numbers in metadata, etc.

   Note that because a single expression is produced, very large
   file loads will fail due to the JVM method size limitation.
   In such cases, see `load-large-file-code'`."
  [file file-path file-name]
  (apply format
         "(clojure.lang.Compiler/load (java.io.StringReader. %s) %s %s)"
         (map (fn [item]
                (binding [*print-length* nil
                          *print-level* nil]
                  (pr-str item)))
              [file file-path file-name])))

(defn- load-file-contents [[file file-path file-name]]
  (Compiler/load (StringReader. file) file-path file-name))

#_{:clj-kondo/ignore [:deprecated-var]}
(defn- load-file-eval-msg
  [{:keys [file file-path file-name ^Transport transport] :as msg}]
  (let [transport (reify Transport
                    (recv [_this] (.recv transport))
                    (recv [_this timeout] (.recv transport timeout))
                    (send [this resp]
                      ;; *ns* is always 'user' after loading a file, so
                      ;; *remove it to avoid confusing tools that assume any
                      ;; *:ns always reports *ns*
                      (.send transport (dissoc resp :ns))
                      this))
        eval-msg (-> (dissoc msg :file :file-name :file-path)
                     (assoc :op "eval", :transport transport))]
    (if (thread-bound? #'load-file-code)
      ;; This approach is left for backward compatibility
      (assoc eval-msg :code (load-file-code file file-path file-name))
      ;; This is a bit of hack to make `eval` mw just call the code we want.
      ;; `:code` receives a tuple of the required arguments, wrapped in another
      ;; vector on top (so `eval` doesn't try to read anything and just use it
      ;; as input to `:eval` function), and we also provide a custom `:eval` to
      ;; delegate loading to `Compiler/load`. This approach sidesteps the method
      ;; size issue.
      (assoc eval-msg
             :code [[file file-path file-name]]
             :eval `load-file-contents))))

(defn wrap-load-file
  "Middleware that evaluates a file's contents, as per load-file,
   but with all data supplied in the sent message (i.e. safe for use
   with remote REPL environments).

   This middleware depends on the availability of an :op \"eval\"
   middleware below it (such as interruptible-eval)."
  [h]
  (fn [{:keys [op] :as msg}]
    (if (not= op "load-file")
      (h msg)
      (h (load-file-eval-msg msg)))))

(set-descriptor! #'wrap-load-file
                 {:requires #{#'caught/wrap-caught #'print/wrap-print}
                  :expects #{"eval"}
                  :handles {"load-file"
                            {:doc "Loads a body of code, using supplied path and filename info to set source file and line number metadata. Delegates to underlying \"eval\" middleware/handler."
                             :requires {"file" "Full contents of a file of code."}
                             :optional (merge caught/wrap-caught-optional-arguments
                                              print/wrap-print-optional-arguments
                                              {"file-path" "Source-path-relative path of the source file, e.g. clojure/java/io.clj"
                                               "file-name" "Name of source file, e.g. io.clj"})
                             :returns (-> (meta #'eval/interruptible-eval)
                                          (get-in [::middleware/descriptor :handles "eval" :returns])
                                          (dissoc "ns"))}}})
