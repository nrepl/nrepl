(ns nrepl.cmdline
  "A proof-of-concept command-line client for nREPL.  Please see
  e.g. REPL-y for a proper command-line nREPL client @
  https://github.com/trptcolin/reply/"
  {:author "Chas Emerick"}
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nrepl.config :as config]
   [nrepl.core :as nrepl]
   [nrepl.ack :refer [send-ack]]
   [nrepl.misc :refer [noisy-future]]
   [nrepl.server :as nrepl-server]
   [nrepl.socket :as socket]
   [nrepl.transport :as transport]
   [nrepl.version :as version])
  (:import
   [java.net URI]))

(defn- clean-up-and-exit
  "Performs any necessary clean up and calls `(System/exit status)`."
  [status]
  (shutdown-agents)
  (flush)
  (binding [*out* *err*] (flush))
  (System/exit status))

(defn exit
  "Requests that the process exit with the given `status`.  Does not
  return."
  [status]
  ;; :nrepl/kind is our shared (ns independent) ExceptionInfo discriminator
  (throw (ex-info nil {:nrepl/kind ::exit ::status status})))

(defn die
  "`Print`s items in `msg` to *err* and then exits with a status of 2."
  [& msg]
  (binding [*out* *err*]
    (doseq [m msg] (print m)))
  (exit 2))

(defmacro ^{:author "Colin Jones"} set-signal-handler!
  [signal f]
  (if (try (Class/forName "sun.misc.Signal")
           (catch Throwable _e))
    `(try
       (sun.misc.Signal/handle
        (sun.misc.Signal. ~signal)
        (proxy [sun.misc.SignalHandler] []
          (handle [signal#] (~f signal#))))
       (catch Throwable e#))
    `(println "Unable to set signal handlers.")))

(def colored-output
  {:err #(binding [*out* *err*]
           (print "\033[31m")
           (print %)
           (print "\033[m")
           (flush))
   :out print
   :value (fn [x]
            (print "\033[34m")
            (print x)
            (println "\033[m")
            (flush))})

(def running-repl (atom {:transport nil
                         :client nil}))

(defn- done? [input]
  (contains? #{'exit 'quit '(exit) '(quit)} input))

(defn repl-intro
  "Returns nREPL interactive repl intro copy and version info as a new-line
  separated string."
  []
  (format "nREPL %s
Clojure %s
%s %s
Interrupt: Control+C
Exit:      Control+D or (exit) or (quit)"
          (:version-string version/version)
          (clojure-version)
          (System/getProperty "java.vm.name")
          (System/getProperty "java.runtime.version")))

(defn- run-repl-with-transport
  [transport {:keys [prompt err out value]
              :or   {prompt #(print (str % "=> "))
                     err    print
                     out    print
                     value  println}}]
  (let [client (nrepl/client transport Long/MAX_VALUE)]
    (println (repl-intro))
    ;; We take 50ms to listen to any greeting messages, and display the value
    ;; in the `:out` slot.
    (noisy-future (->> (client)
                       (take-while #(nil? (:id %)))
                       (run! #(when-let [msg (:out %)] (print msg)))))
    (Thread/sleep 50)
    (let [session (nrepl/client-session client)]
      (swap! running-repl assoc :transport transport)
      (swap! running-repl assoc :client session)
      (binding [*ns* *ns*]
        (loop []
          (prompt *ns*)
          (flush)
          (let [input (read *in* false 'exit)]
            (if (done? input)
              (clean-up-and-exit 0)
              (do (doseq [res (nrepl/message session {:op "eval" :code (pr-str input)})]
                    (when (:value res) (value (:value res)))
                    (when (:out res) (out (:out res)))
                    (when (:err res) (err (:err res)))
                    (when (:ns res) (set! *ns* (create-ns (symbol (:ns res))))))
                  (recur)))))))))

(defn- run-repl
  ([{:keys [server options]}]
   (let [{:keys [host port socket] :or {host "127.0.0.1"}} server
         {:keys [transport-fn tls-keys-file tls-keys-str]
          :or {transport-fn #'transport/bencode}} options]
     (run-repl-with-transport
      (cond
        socket
        (nrepl/connect :socket socket :transport-fn transport-fn)

        (and host port)
        (nrepl/connect :host host :port port :transport-fn transport-fn
                       :tls-keys-file tls-keys-file :tls-keys-str tls-keys-str)

        :else
        (die "Must supply host/port or socket."))
      options)))
  ([host port]
   (run-repl host port nil))
  ([host port options]
   (run-repl {:server  (cond-> {}
                         host (assoc :host host)
                         port (assoc :port port))
              :options options})))

(def #^{:private true} option-shorthands
  {"-i" "--interactive"
   "-r" "--repl"
   "-f" "--repl-fn"
   "-c" "--connect"
   "-b" "--bind"
   "-h" "--host"
   "-p" "--port"
   "-s" "--socket"
   "-m" "--middleware"
   "-t" "--transport"
   "-n" "--handler"
   "-v" "--version"})

(def #^{:private true} unary-options
  #{"--interactive"
    "--connect"
    "--color"
    "--help"
    "--version"
    "--verbose"})

(defn- expand-shorthands
  "Expand shorthand options into their full forms."
  [args]
  (map (fn [arg] (or (option-shorthands arg) arg)) args))

(defn- keywordize-options [options]
  (let [options (reduce-kv
                 #(assoc %1 (keyword (str/replace-first %2 "--" "")) %3)
                 {}
                 options)
        ;; Rename CLI arg --transport canonical *-fn keys.
        transport-fn (:transport options)
        ack-port (:ack options)]
    (cond-> (dissoc options :transport)
      transport-fn (assoc :transport-fn transport-fn)
      ack-port (assoc :ack-port ack-port))))

(defn- split-args
  "Convert `args` into a map of options + a list of args.
  Unary options are set to true during this transformation.
  Returns a vector combining the map and the list."
  [args]
  (loop [[arg & rem-args :as args] args
         options {}]
    (if-not (and arg (re-matches #"-.*" arg))
      [options args]
      (if (unary-options arg)
        (recur rem-args
               (assoc options arg true))
        (recur (rest rem-args)
               (assoc options arg (first rem-args)))))))

(defn help
  []
  ;; Updating this? Remember to also update server.adoc
  "Usage:

  -i/--interactive            Start nREPL and connect to it with the built-in client.
  -c/--connect                Connect to a running nREPL with the built-in client.
  -C/--color                  Use colors to differentiate values from output in the REPL. Must be combined with --interactive.
  -b/--bind ADDR              Bind address, by default \"127.0.0.1\".
  -h/--host ADDR              Host address to connect to when using --connect. Defaults to \"127.0.0.1\".
  -p/--port PORT              Start nREPL on PORT. Defaults to 0 (random port) if not specified.
  -s/--socket PATH            Start nREPL on filesystem socket at PATH or nREPL to connect to when using --connect.
  --ack ACK-PORT              Acknowledge the port of this server to another nREPL server running on ACK-PORT.
  -n/--handler HANDLER        The nREPL message handler to use for each incoming connection; defaults to the result of `(nrepl.server/default-handler)`. Must be expressed as a namespace-qualified symbol. The underlying var will be automatically `require`d.
  -m/--middleware MIDDLEWARE  A sequence of vars (expressed as namespace-qualified symbols), representing middleware you wish to mix in to the nREPL handler. The underlying vars will be automatically `require`d.
  -t/--transport TRANSPORT    The transport to use (default `nrepl.transport/bencode`), expressed as a namespace-qualified symbol. The underlying var will be automatically `require`d.
  --help                      Show this help message.
  -v/--version                Display the nREPL version.
  --verbose                   Show verbose output.")

(defn- require-and-resolve
  "Attempts to resolve the config `key`'s `value` as a namespaced symbol
  and returns the related var if successful.  Otherwise calls `die`."
  [key sym]
  (when-not (symbol? sym)
    (die (format "nREPL %s: %s is not a symbol\n" (name key) (pr-str sym))))
  (let [space (some-> (namespace sym) symbol)]
    (when-not space
      (die (format "nREPL %s: %s has no namespace\n" (name key) sym)))
    (require space)
    (or (ns-resolve space (-> sym name symbol))
        (die (format "nREPL %s: unable to resolve %s\n" (name key) sym)))))

(def ^:private resolve-mw-xf
  (comp (map #(require-and-resolve :middleware %))
        (keep identity)))

(defn- handle-seq-var
  [var]
  (let [x @var]
    (if (sequential? x)
      (into [] resolve-mw-xf x)
      [var])))

(defn- handle-interrupt
  [_signal]
  (let [transport (:transport @running-repl)
        client (:client @running-repl)]
    (if (and transport client)
      (doseq [res (nrepl/message client {:op "interrupt"})]
        (when (= ["done" "session-idle"] (:status res))
          (System/exit 0)))
      (System/exit 0))))

(def ^:private mw-xf
  (comp (map symbol)
        resolve-mw-xf
        (mapcat handle-seq-var)))

(defn- ->mw-list
  "Flatten `middleware` which is a sequence of vars that representing middleware
  you wish to mix in to the nREPL handler. Vars can resolve to a sequence of
  vars, in which case they'll be flattened into the list of middleware."
  [middleware]
  (into [] mw-xf middleware))

(defn args->options
  "Takes CLI args list and returns vector of parsed options map merged with global
  config and remaining args. Resolves delayed vars in the options map."
  [args]
  (let [[cli-options _args] (split-args (expand-shorthands args))
        cli-options (keywordize-options cli-options)]
    (config/fill-from-map cli-options)
    (let [options (merge cli-options (config/get-config))
          middleware (some-> (:middleware options) force ->mw-list)
          handler (some-> (:handler options) force)
          repl-fn @(:repl-fn options)
          transport-fn @(:transport-fn options)
          ack-port (:ack-port options)
          options (cond-> (assoc options
                                 :repl-fn repl-fn
                                 :transport-fn transport-fn
                                 :greeting-fn (when (= transport-fn #'transport/tty)
                                                #'transport/tty-greeting))
                    handler (assoc :handler handler)
                    middleware (assoc :middleware middleware)
                    ;; Compatibility
                    ack-port (assoc :ack ack-port))]
      [options _args])))

(defn display-help
  "Prints the help copy to the screen and exits the program with exit code 0."
  []
  (println (help))
  (exit 0))

(defn display-version
  "Prints nREPL version to the screen and exits the program with exit code 0."
  []
  (println (:version-string version/version))
  (exit 0))

(defn interactive-repl
  "Runs an interactive repl. Takes nREPL server map and processed options map."
  [server {:keys [transport-fn repl-fn color] :as options}]
  (let [repl-options (merge (when color colored-output)
                            {:transport-fn transport-fn
                             ;; Left for backcompat.
                             :transport transport-fn})
        {:keys [host port socket]} server]
    (when (= transport-fn #'transport/tty)
      (die "The built-in client does not support the tty transport. Consider using `nc` or `telnet`.\n"))
    (if socket
      (repl-fn {:server  server
                :options repl-options})
      (repl-fn host port
               (merge repl-options
                      (select-keys options [:tls-keys-str :tls-keys-file]))))))

(defn connect-to-server
  "Connects to a running nREPL server and runs a REPL. Exits program when REPL
  is closed.
  Takes a map of nREPL CLI options."
  [{:keys [host port socket] :as options}]
  (interactive-repl {:host   host
                     :port   port
                     :socket socket}
                    options)
  (exit 0))

(defn ack-server
  "Acknowledge the port of this server to another nREPL server running on
  `:ack-port`. Takes nREPL server map and processed CLI options map."
  [server {:keys [ack-port transport-fn verbose] :as _options}]
  (when ack-port
    (let [port (:port server)]
      (when verbose
        (println (format "ack'ing my port %d to other server running on port %d"
                         port ack-port)))
      (send-ack port ack-port transport-fn))))

(defn server-started-message
  "Returns nREPL server started message that some tools rely on to parse the
  connection details from.
  Takes nREPL server map and processed CLI options map.
  Returns connection header string."
  [server {:keys [transport-fn] :as _options}]
  (let [^java.net.ServerSocket ssocket (:server-socket server)
        ^URI uri (socket/as-nrepl-uri ssocket (transport/uri-scheme transport-fn))]
    ;; The format here is important, as some tools (e.g. CIDER) parse the string
    ;; to extract from it the host and the port to connect to
    (if-let [host (.getHost uri)]
      (format "nREPL server started on port %d on host %s - %s"
              (.getPort uri) host uri)
      (str "nREPL server started on socket " (.toASCIIString uri)))))

(defn save-port-file
  "Writes a file relative to project classpath with port number so other tools
  can infer the nREPL server port.
  Takes nREPL server map and processed CLI options map.
  Returns nil."
  [server _options]
  ;; Many clients look for this file to infer the port to connect to
  (let [port (:port server)
        port-file (io/file ".nrepl-port")]
    (.deleteOnExit ^java.io.File port-file)
    (spit port-file port)))

(defn start-server
  "Creates an nREPL server instance.
  Takes map of CLI options.
  Returns nREPL server map."
  [{:keys [port bind socket handler middleware transport-fn greeting-fn
           tls-keys-str tls-keys-file]}]
  (nrepl-server/start-server
   :port port
   :bind bind
   :socket socket
   :handler handler
   :middleware middleware
   :transport-fn transport-fn
   :greeting-fn greeting-fn
   :tls-keys-str tls-keys-str
   :tls-keys-file tls-keys-file))

(defn dispatch-commands
  "Look at options to dispatch a specified command.
  Takes CLI options map. May return a server map, nil, or exit."
  [options]
  (cond (:help options)    (display-help)
        (:version options) (display-version)
        (:connect options) (connect-to-server options)
        :else (let [server (start-server options)]
                (ack-server server options)
                (println (server-started-message server options))
                (save-port-file server options)
                (if (:interactive options)
                  (interactive-repl server options)
                  ;; need to hold process open with a non-daemon thread
                  @(promise)))))

(defn -main
  [& args]
  (try
    (set-signal-handler! "INT" handle-interrupt)
    (let [[options _args] (args->options args)]
      (dispatch-commands options))
    (catch clojure.lang.ExceptionInfo ex
      (let [{:keys [:nrepl/kind ::status]} (ex-data ex)]
        (case kind
          ::exit (clean-up-and-exit status)
          (:nrepl.server/no-filesystem-sockets
           :nrepl.server/invalid-start-request)
          (do
            (binding [*out* *err*]
              (println (.getMessage ex)))
            (clean-up-and-exit 2))
          (throw ex))))))
