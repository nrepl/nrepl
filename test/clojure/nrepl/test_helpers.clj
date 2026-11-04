(ns nrepl.test-helpers
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [nrepl.spec]
            [nrepl.transport :as transport])
  (:import (com.hypirion.io ClosingPipe Pipe)
           (java.io File)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security SecureRandom)))

(comment
  matcher-combinators.test/keep-me ;; needed for `match?`
  nrepl.spec/keep-me ;; Load for side effects (register specs).
  )

(def sys-newline
  "The system's newline character sequence."
  (System/lineSeparator))

(def win?
  "Whether we are running on MS-Windows."
  (.startsWith (System/getProperty "os.name") "Windows"))

(defn string=
  "Like `clojure.core/=` applied on STRING1 and STRING2, but treats
  all line endings as equal."
  [string1 string2]
  (= (str/split-lines string1) (str/split-lines string2)))

(defn newline->sys
  "Returns TEXT, converting `\n` line endings to those of the underlying
  system's, if different."
  [text]
  (if-not (= sys-newline "\n")
    (str/replace text "\n" sys-newline)
    text))

(def sys-file-sep
  "The character separating components in a path."
  File/separator)

(defn dir-sep->sys
  "Replace the `/` separator component used in tests with the one used
  by the system."
  [path]
  (if-not (= sys-file-sep "/")
    (str/replace path "/" sys-file-sep)
    path))

(defn not-blank?
  "Return true iff `s` is a string and it's not empty or just whitespace."
  [s]
  (and (string? s) (not (str/blank? s))))

(defn sh
  "A version of clojure.java.shell/sh that streams in/out/err.
  Taken and edited from https://github.com/technomancy/leiningen/blob/f7e1adad6ff5137d6ea56bc429c3b620c6f84128/leiningen-core/src/leiningen/core/eval.clj"
  ^Process
  [cmd]
  (let [proc (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String cmd))]
    (future (with-open [out (.getInputStream proc)
                        err (.getErrorStream proc)
                        in (.getOutputStream proc)]
              (let [_pump-out (doto (Pipe. out System/out) .start)
                    _pump-err (doto (Pipe. err System/err) .start)
                    _pump-in (ClosingPipe. System/in in)]
                (.waitFor proc))))
    proc))

(defmacro with-process [[binding cmd-vector] & body]
  (let [sym (gensym)]
    `(let [~sym (sh ~cmd-vector)
           ~binding ~sym]
       (try ~@body
            (finally (.destroy ~binding))))))

(defmacro with-timeout
  "Runs BODY on another thread and gives up after MS milliseconds, throwing
  instead of hanging the whole test run. For code that blocks on responses
  with no timeout of its own, like the built-in client."
  [ms & body]
  `(let [f# (future ~@body)
         v# (deref f# ~ms ::timeout)]
     (if (identical? v# ::timeout)
       (do (future-cancel f#)
           (throw (ex-info (str "Timed out after " ~ms "ms") {:timeout-ms ~ms})))
       v#)))

(defn wait-for
  "Poll `pred` every 50 ms until it returns a truthy value or nil if
  `timeout-ms` (default: 5000) elapses."
  ([pred] (wait-for pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (pred)
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 50)
             (recur)))))))

(defn free-port []
  (let [sock (ServerSocket. 0)
        port (.getLocalPort sock)]
    (.close sock)
    port))

;; nrepl-specific helpers

(def tested-transports [{:name :bencode
                         :fn #'transport/bencode
                         :slug "nrepl"
                         :strict? false}
                        {:name :edn
                         :fn #'transport/edn
                         :slug "nrepl+edn"
                         :strict? true}])

(defmacro with-every-transport [& body]
  `(reduce (fn [_# t#]
             (binding [*transport* t#]
               (clojure.test/testing (format "%s -" (:name t#))
                 ~@body)))
           nil tested-transports))

(def ^:dynamic *transport* (first tested-transports))
(def ^:dynamic *server* nil)

(def ^:private side-state
  "Used to pass state from inside embedded nREPL server to outside process."
  (atom {}))

(defn capture-state [key value] (swap! side-state assoc key value))

(defn get-state [key] (@side-state key))

(def ^:private ^SecureRandom rng (SecureRandom.))
(def ^:private sockets-dir
  (let [dir (Files/createTempDirectory "s" (into-array FileAttribute []))]
    (.deleteOnExit (.toFile dir))
    dir))

(defn- random-name
  "13 chars * 5 bits = 65 bits of entropy."
  []
  (str/join (repeatedly 13 #(nth "abcdefghijklmnopqrstuvwxyz234567" (.nextInt rng 32)))))

(defn temp-file-name []
  (str (io/file (str sockets-dir) (random-name))))

(defn with-nrepl-server* [config f]
  (assert *transport* "*transport* must be bound")
  (let [sock-file (when-not (:port config)
                    (temp-file-name))
        config (cond-> (assoc config :transport-fn (:fn *transport*))
                 sock-file (assoc :socket sock-file))]
    (with-open [^java.lang.AutoCloseable server (apply server/start-server (mapcat identity config))]
      (binding [*server* server]
        (reset! side-state {})
        (f)))))

(defmacro with-nrepl-server [config & body]
  (if (:tcp-and-unix config)
    `(let [f# (fn [] ~@body)]
       (testing "unix" (with-nrepl-server* ~config f#))
       (testing "tcp" (with-nrepl-server* ~(assoc config :port 0) f#)))
    `(with-nrepl-server* ~config (fn [] ~@body))))

(defn -connect-to-*server* ^java.lang.AutoCloseable []
  (assert *server* "*server* must be bound")
  (apply nrepl/connect :transport-fn (:fn *transport*)
         (if (:port *server*)
           [:port (:port *server*)]
           [:socket (:socket *server*)])))

(defn- check-response-format
  "Check response against nrepl.spec."
  [resp]
  (clojure.spec.alpha/assert* :nrepl.spec/message resp)
  resp)

(defn clean-response
  "Cleans a response to help testing.

  This manually coerces bencode responses to (close) to what the raw EDN
  response is, so we can standardise testing around the richer format. It
  retains strictness on EDN transports.

  - ensures the status to a set of keywords
  - turn the content of truncated-keys to keywords"
  [resp]
  (letfn [(normalize-newlines [resp key]
            (cond-> resp
              (contains? resp key) (update key str/replace "\r\n" "\n")))
          (normalize-status [resp]
            (cond-> resp
              (:status resp) (update :status #(set (map keyword %)))))]
    (cond-> resp
      true                         (normalize-newlines :out)
      true                         (normalize-newlines :err)
      (:strict? *transport*)       check-response-format
      (not (:strict? *transport*)) normalize-status)))

(defmacro with-nrepl-client [& body]
  `(with-open [transport# (-connect-to-*server*)]
     (let [~'transport transport#
           ;; Set timeout to 10 sec to prevent stuck tests. Each test never runs for longer.
           ~'client (nrepl/client transport# 10000)
           ~'session (nrepl/client-session ~'client)
           ~'message (fn this#
                       ([msg#] (this# ~'session msg#))
                       ([cli# msg#]
                        (map clean-response (nrepl/message cli# msg#))))
           ~'message! (fn this#
                        ([msg#] (this# ~'session msg#))
                        ([cli# msg#]
                         (nrepl/combine-responses (~'message cli# msg#))))
           ~'repl-eval #(~'message %1 {:op "eval" :code %2})
           ~'repl-eval! (fn this#
                          ([code#] (this# ~'session code#))
                          ([cli# code#]
                           (nrepl/combine-responses (~'repl-eval cli# code#))))
           ~'repl-values (fn this#
                           ([code#] (this# ~'session code#))
                           ([cli# code#] (mapv read-string (:value (~'repl-eval! cli# code#)))))
           ~'repl-value1 (comp first ~'repl-values)]
       ~@body)))

(defn session-id [session]
  (:session (::nrepl/taking-until (meta session))))

(defn eval-value1
  "Transform `code-form` into a string, send to `client-or-session` via `eval` op,
  read and combine the response and return the first successful value."
  [client-or-session code-form]
  (-> (nrepl/message client-or-session {:op "eval", :code (nrepl/code* code-form)})
      nrepl/response-values
      first))

(defmacro def-repl-test
  {:style/indent 1}
  [name & body]
  `(deftest ~name
     (with-every-transport
       (with-nrepl-server ~(if (:tcp-and-unix (meta name))
                             {:tcp-and-unix true} {})
         (with-nrepl-client
           ~@body)))))

(defmacro is+
  "Like `is` but wraps expected value in matcher-combinators's `match?`."
  ([expected actual]
   `(is+ ~expected ~actual nil))
  ([expected actual message]
   `(is (~'match? ~expected ~actual) ~message)))
