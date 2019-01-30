(ns nrepl.sanity-test
  (:require
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.middleware.interruptible-eval :as eval]
   [nrepl.middleware.print :as print]
   [nrepl.middleware.session :as session]
   [nrepl.misc :as misc]
   [nrepl.transport :as transport :refer [piped-transports]])
  (:import
   (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(println (format "Testing with Clojure v%s on %s" (clojure-version) (System/getProperty "java.version")))

(defn- internal-eval
  ([expr] (internal-eval nil expr))
  ([ns expr]
   (let [[local remote] (piped-transports)
         expr (if (string? expr)
                expr
                (binding [*print-meta* true]
                  (pr-str expr)))
         msg (cond-> {:code expr :transport remote :session (atom {})}
               ns (assoc :ns ns))]
     (eval/evaluate msg)
     (-> (nrepl/response-seq local 0)
         (nrepl/combine-responses)
         (select-keys [:ns :value :out :err])))))

(deftest eval-sanity
  (try
    (are [result expr] (= result (internal-eval expr))
      {:ns "user" :value [3]}
      '(+ 1 2)

      {:ns "user" :value [nil]}
      '*1

      {:ns "user" :value [nil]}
      '(do (def ^{:dynamic true} ++ +) nil)

      {:ns "user" :value [5]}
      '(binding [++ -] (++ 8 3))

      {:ns "user" :value [42]}
      '(set! *print-length* 42)

      {:ns "user" :value [nil]}
      '*print-length*)
    (finally (ns-unmap *ns* '++))))

(deftest specified-namespace
  (try
    (are [ns result expr] (= result (internal-eval ns expr))
      (ns-name *ns*)
      {:ns "user" :value [3]}
      '(+ 1 2)

      'user
      {:ns "user" :value '[("user" "++")]}
      '(do
         (def ^{:dynamic true} ++ +)
         (map #(-> #'++ meta % str) [:ns :name]))

      (ns-name *ns*)
      {:ns "user" :value [5]}
      '(binding [user/++ -]
         (user/++ 8 3)))
    (finally (ns-unmap 'user '++))))

(deftest multiple-expressions
  (are [result expr] (= result (internal-eval expr))
    {:ns "user" :value [4 65536.0]}
    "(+ 1 3) (Math/pow 2 16)"

    {:ns "user" :value [4 20 1 0]}
    "(+ 2 2) (* *1 5) (/ *2 4) (- *3 4)"

    {:ns "user" :value [nil]}
    '*1))

(deftest repl-out-writer
  (let [[local remote] (piped-transports)
        w (print/replying-PrintWriter :out {:transport remote} {})]
    (doto w
      .flush
      (.println "println")
      (.write "abcd")
      (.write (.toCharArray "ef") 0 2)
      (.write "gh" 0 2)
      (.write (.toCharArray "ij"))
      (.write "   klm" 5 1)
      (.write 32)
      .flush)
    (with-open [out (java.io.PrintWriter. w)]
      (binding [*out* out]
        (newline)
        (prn #{})
        (flush)))

    (is (= [(str "println" (System/getProperty "line.separator"))
            "abcdefghijm "
            "\n#{}\n"]
           (->> (nrepl/response-seq local 0)
                (map :out))))))
