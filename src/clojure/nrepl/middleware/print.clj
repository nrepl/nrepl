(ns nrepl.middleware.print
  "Support for configurable printing. See the docstring of `wrap-print` and the
  Pretty Printing section of the Middleware documentation for more information."
  {:author "Michael Griffiths"}
  (:refer-clojure :exclude [print])
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.transport :as transport])
  (:import
   (java.io BufferedWriter PrintWriter StringWriter Writer)
   (nrepl QuotaExceeded)
   (nrepl.transport Transport)))

(defn- to-char-array
  ^chars
  [x]
  (cond
    (string? x) (.toCharArray ^String x)
    (integer? x) (char-array [(char x)])
    :else x))

(defn with-quota-writer
  "Returns a `java.io.Writer` that wraps `writer` and throws `QuotaExceeded` once
  it has written more than `quota` bytes."
  ^java.io.Writer
  [^Writer writer quota]
  (if-not quota
    writer
    (let [total (volatile! 0)]
      (proxy [Writer] []
        (toString []
          (.toString writer))
        (write
          ([x]
           (let [cbuf (to-char-array x)]
             (.write ^Writer this cbuf (int 0) (count cbuf))))
          ([x off len]
           (locking total
             (let [cbuf (to-char-array x)
                   rem (- quota @total)]
               (vswap! total + len)
               (.write writer cbuf ^int off ^int (min len rem))
               (when (neg? (- rem len))
                 (throw (QuotaExceeded.)))))))
        (flush []
          (.flush writer))
        (close []
          (.close writer))))))

(defn replying-PrintWriter
  "Returns a `java.io.PrintWriter` suitable for binding as `*out*` or `*err*`. All
  of the content written to that `PrintWriter` will be sent as messages on the
  transport of `msg`, keyed by `key`."
  ^java.io.PrintWriter
  [key {:keys [transport] :as msg} {:keys [::buffer-size ::quota] :as opts}]
  (-> (proxy [Writer] []
        (write
          ([x]
           (let [cbuf (to-char-array x)]
             (.write ^Writer this cbuf (int 0) (count cbuf))))
          ([x off len]
           (let [cbuf (to-char-array x)
                 text (str (doto (StringWriter.)
                             (.write cbuf ^int off ^int len)))]
             (when (pos? (count text))
               (transport/send transport (misc/response-for msg key text))))))
        (flush [])
        (close []))
      (BufferedWriter. (or buffer-size 1024))
      (with-quota-writer quota)
      (PrintWriter. true)))

;; private in clojure.core
(defn- pr-on
  [x w _]
  (if *print-dup*
    (print-dup x w)
    (print-method x w))
  nil)

(defn- send-streamed
  [{:keys [::print-fn transport] :as msg}
   {:keys [::keys] :as resp}]
  (let [print-key (fn [key]
                    (let [value (get resp key)]
                      (try
                        (with-open [writer (replying-PrintWriter key msg)]
                          (print-fn value writer))
                        (catch QuotaExceeded _
                          (transport/send
                           transport
                           (misc/response-for msg :status ::truncated))))))]
    (run! print-key keys))
  (transport/send transport (apply dissoc resp (conj keys ::keys))))

(defn- send-nonstreamed
  [{:keys [::print-fn transport] :as msg}
   {:keys [::keys] :as resp}]
  (let [print-key (fn [key]
                    (let [value (get resp key)
                          writer (-> (StringWriter.)
                                     (with-quota-writer msg))
                          truncated? (volatile! false)]
                      (try
                        (print-fn value writer)
                        (catch QuotaExceeded _
                          (vreset! truncated? true)))
                      [key (str writer) @truncated?]))
        rf (completing
            (fn [resp [key printed-value truncated?]]
              (cond-> (assoc resp key printed-value)
                truncated? (update ::truncated-keys (fnil conj []) key))))
        resp (transduce (map print-key) rf resp keys)]
    (transport/send transport (cond-> (dissoc resp ::keys)
                                (::truncated-keys resp)
                                (update :status conj ::truncated)))))

(defn- printing-transport
  [{:keys [transport ::stream?] :as msg}]
  (reify Transport
    (recv [this]
      (transport/recv transport))
    (recv [this timeout]
      (transport/recv transport timeout))
    (send [this resp]
      (if stream?
        (send-streamed msg resp)
        (send-nonstreamed msg resp))
      this)))

(defn- resolve-print
  [{:keys [::print transport] :as msg}]
  (when-let [var-sym (some-> print (symbol))]
    (let [print-var (try
                      (require (symbol (namespace var-sym)))
                      (resolve var-sym)
                      (catch Exception _))]
      (when-not print-var
        (let [resp {:status ::error
                    ::error (str "Couldn't resolve var " var-sym)}]
          (transport/send transport (misc/response-for msg resp))))
      print-var)))

(defn wrap-print
  "Middleware that provides printing functionality to other middlewares.

  Returns a handler which transforms any slots specified by
  `:nrepl.middleware.print/keys` in messages sent via the request's transport to
  strings using the provided printing function and options.

  Supports the following options:

  * `::print` – a fully-qualified symbol naming a var whose function to use for
  printing. Defaults to the equivalent of `clojure.core/pr`. Must point to a
  function with signature [value writer options].

  * `::options` – a map of options to pass to the printing function. Defaults to
  `nil`.

  * `::stream?` – if logical true, the result of printing each value will be
  streamed to the client over one or more messages.

  * `::buffer-size` – the size of the buffer to use when streaming results.
  Defaults to 1024.

  * `::quota` – a hard limit on the number of bytes printed for each value."
  [handler]
  (fn [{:keys [::options] :as msg}]
    (let [print-var (or (resolve-print msg) #'pr-on)
          print (fn [value writer]
                  (print-var value writer options))
          msg (assoc msg ::print-fn print)
          transport (printing-transport msg)]
      (handler (assoc msg :transport transport)))))

(set-descriptor! #'wrap-print {:requires #{}
                               :expects #{}
                               :handles {}})
