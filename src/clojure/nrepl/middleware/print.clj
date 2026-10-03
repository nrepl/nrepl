(ns nrepl.middleware.print
  "Support for configurable printing. See the docstring of `wrap-print` and the
  Pretty Printing section of the Middleware documentation for more information."
  {:author "Michael Griffiths"
   :added  "0.6"}
  (:refer-clojure :exclude [print])
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.transport :as transport])
  (:import
   (clojure.lang RT)
   (java.io OutputStreamWriter PrintWriter StringWriter Writer)
   (nrepl.out CallbackBufferedOutputStream QuotaBoundWriter QuotaExceeded)
   (nrepl.transport Transport)))

(def ^:dynamic *print-fn*
  "Function to use for printing. Takes two arguments: `value`, the value to print,
  and `writer`, the `java.io.PrintWriter` to print on.

  Defaults to the equivalent of `clojure.core/pr`."
  @#'clojure.core/pr-on) ;; Private in clojure.core

(def ^:dynamic *stream?*
  "If logical true, the result of printing each value will be streamed to the
  client over one or more messages. Defaults to false."
  false)

(def ^:dynamic *buffer-size*
  "The size of the buffer to use when streaming results. Defaults to 1024."
  1024)

(def ^:dynamic *quota*
  "A hard limit on the number of bytes printed for each value. Defaults to nil. No
  limit will be used if not set."
  nil)

(def configuration-keys
  [::print-fn ::stream? ::buffer-size ::quota ::keys])

(defn with-quota-bound-writer
  "Returns a `java.io.Writer` that wraps `writer` and throws `QuotaExceeded` once
  it has written more than `quota` bytes."
  ^Writer
  [^Writer writer quota]
  (if quota
    (QuotaBoundWriter. writer quota)
    writer))

(defn replying-PrintWriter
  "Returns a `java.io.PrintWriter` suitable for binding as `*out*` or `*err*`. All
  of the content written to that `PrintWriter` will be sent as messages on the
  transport of `msg`, keyed by `key`."
  ^java.io.PrintWriter
  [key msg opts]
  (-> (CallbackBufferedOutputStream. #(transport/respond-to msg key %)
                                     (or (::buffer-size opts) *buffer-size* 1024))
      (OutputStreamWriter.)
      (with-quota-bound-writer (::quota opts *quota*))
      (PrintWriter. true)))

(defn- send-streamed
  [msg resp print-fn {:keys [::keys] :as opts}]
  ;; Iterator is used instead of reduce for cleaner stacktrace if an exception
  ;; gets thrown during printing.
  (let [it (RT/iter keys)]
    (while (.hasNext it)
      (let [key (.next it)
            value (get resp key)]
        (try (with-open [writer (replying-PrintWriter key msg opts)]
               (print-fn value writer))
             (catch QuotaExceeded _
               (transport/respond-to msg :status ::truncated))))))
  (transport/respond-to msg (apply dissoc resp keys)))

(defn- send-nonstreamed
  [msg resp print-fn {:keys [::keys] :as opts}]
  ;; Iterator is used instead of reduce for cleaner stacktrace if an exception
  ;; gets thrown during printing.
  (let [it (RT/iter keys)]
    (loop [resp resp]
      (if (.hasNext it)
        (let [key (.next it)
              value (get resp key)
              writer (with-quota-bound-writer (StringWriter.) (::quota opts *quota*))
              truncated? (volatile! false)]
          (try (print-fn value writer)
               (catch QuotaExceeded _
                 (vreset! truncated? true)))
          (recur (cond-> (assoc resp key (str writer))
                   @truncated? (update ::truncated-keys (fnil conj []) key))))

        (transport/respond-to msg (cond-> resp
                                    (::truncated-keys resp)
                                    (update :status #(set (conj % ::truncated)))))))))

(defn- resolve-print
  [{:keys [::print ::options] :as msg}]
  (when-let [var-sym (some-> print (symbol))]
    (if-let [print-var (try
                         (requiring-resolve var-sym)
                         ;; The symbol comes from the client, so a missing
                         ;; namespace or an unqualified name is a user error.
                         (catch Exception _ nil))]
      (fn [value writer]
        (print-var value writer options))
      (do (transport/respond-to msg {::error (str "Couldn't resolve var " var-sym)
                                     :status ::error})
          nil))))

(defn- printing-transport
  [{:keys [transport] :as msg}]
  (let [print-fn-from-req (resolve-print msg)]
    (reify Transport
      (recv [_this]
        (transport/recv transport))
      (recv [_this timeout]
        (transport/recv transport timeout))
      (send [this resp]
        ;; Request config has priority over response config.
        (let [print-fn (or print-fn-from-req
                           (::print-fn resp)
                           (misc/resolve-in-session msg *print-fn*))
              opts (merge resp msg)
              resp (apply dissoc resp configuration-keys)]
          (if (::stream? opts *stream?*)
            (send-streamed msg resp print-fn opts)
            (send-nonstreamed msg resp print-fn opts)))
        this))))

(defn wrap-print
  "Middleware that provides printing functionality to other middlewares.

  Returns a handler which transforms any slots specified by
  `:nrepl.middleware.print/keys` in messages sent via the request's transport to
  strings using the provided printing function and options.

  Supports the following options:

  * `::print` – a fully-qualified symbol naming a var whose function to use for
  printing. Must point to a function with signature [value writer options].

  * `::options` – a map of options to pass to the printing function. Defaults to
  `nil`.

  * `::print-fn` – the function to use for printing. In requests, will be
  resolved from the above two options (if provided). Defaults to the equivalent
  of `clojure.core/pr`. Must have signature [writer options].

  * `::stream?` – if logical true, the result of printing each value will be
  streamed to the client over one or more messages.

  * `::buffer-size` – the size of the buffer to use when streaming results.
  Defaults to 1024.

  * `::quota` – a hard limit on the number of bytes printed for each value.

  * `::keys` – a seq of the keys in the response whose values should be printed.

  The options may be specified in either the request or the responses sent on
  its transport. If any options are specified in both, those in the request will
  be preferred."
  [handler]
  (fn [msg]
    (handler (assoc msg :transport (printing-transport msg)))))

(set-descriptor! #'wrap-print {:requires #{"clone"}
                               :expects #{}
                               :handles {}
                               :session-dynvars #{#'*print-fn* #'*stream?*
                                                  #'*buffer-size* #'*quota*}})

(def wrap-print-optional-arguments
  {"nrepl.middleware.print/print" "A fully-qualified symbol naming a var whose function to use for printing. Must point to a function with signature [value writer options]."
   "nrepl.middleware.print/options" "A map of options to pass to the printing function. Defaults to `nil`."
   "nrepl.middleware.print/stream?" "If logical true, the result of printing each value will be streamed to the client over one or more messages."
   "nrepl.middleware.print/buffer-size" "The size of the buffer to use when streaming results. Defaults to 1024."
   "nrepl.middleware.print/quota" "A hard limit on the number of bytes printed for each value."
   "nrepl.middleware.print/keys" "A seq of the keys in the response whose values should be printed."})
