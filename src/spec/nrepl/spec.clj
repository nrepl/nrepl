(ns nrepl.spec
  "This serves as a reference for messages as implemented by the default nREPL
  middleware. This is not a formal specification."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; Common

;; Op is a string
;; `session` is only generated by nREPL itself, and thus will be a UUID.
;; `id` is provided by the client, and is any string
;; `status` is the return status for a message

(s/def ::op string?)

(s/def ::uuid-str #(and (string? %)
                        (re-matches #"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
                                    (str/lower-case %))))

(s/def ::session (s/or :single ::uuid-str
                       :multi  (s/coll-of ::uuid-str :kind? set?)))

(s/def ::id string?)

(s/def ::status (s/or :single keyword?
                      :multi  (s/coll-of keyword? :kind? set?)))

;; Parameters for Clone

;; `session` is optionally provided.
;; `client-name`, is optionally provided
;; `client-version`, is optionally provided
;; `new-session`, an UUID, is returned

(s/def ::new-session ::uuid-str)

(s/def ::client-name string?)

(s/def ::client-version string?)

;; Parameters for Describe

;; `verbose` is optionally provided

;; `versions` for Java, Clojure, and nREPL are returned.
;; `ops` for available ops is also returned.
;; `aux` is also provided, but not included in spec here.

(s/def ::verbose? boolean?)

(s/def ::doc string?)

(s/def ::requires (s/map-of keyword? string?))

(s/def ::optional (s/map-of keyword? string?))

(s/def ::returns (s/map-of keyword? string?))

(s/def ::description (s/keys :opt-un [::doc ::requires ::optional ::returns]))

(s/def ::ops (s/map-of keyword? ::description))

(s/def ::major (s/or :integer int?
                     :string  string?))

(s/def ::minor (s/or :integer int?
                     :string  string?))

(s/def ::incremental (s/or :integer int?
                           :string  string?))

(s/def ::version (s/keys :opt-un [::major ::minor ::incremental ::version-string]))

(s/def ::versions (s/map-of keyword? ::version))

;; Parameters for Eval

;; `code` is provided as a string.
;; `line`, `column` and `file` are provided for location in source

;; `value` holds the return value
;; `out` holds what was printed to stdout
;; `ns` holds the value of `*ns*` after the eval.

(s/def ::code string?)

(s/def ::out string?)

(s/def ::value any?)

(s/def ::line int?)

(s/def ::column int?)

(s/def ::file string?)

(s/def ::ex string?)

(s/def ::root-ex string?)

(s/def ::ns string?)

;; TODO spec the print inputs/options

;; TODO spec the caught inputs/output

;; Parameters for Load File

;; `file-name` and `file-path` are required.
;; Return params are similar to `eval`

(s/def ::file-name string?)

(s/def ::file-path string?)

;; Parameters for List Sessions

;; `sessions`: Returns sessions in a set

(s/def ::sessions (s/coll-of ::uuid-str :kind? set?))

;; Parameters for Std In

;; `stdin`: string value to be piped into std-in

(s/def ::stdin string?)

;; Message map def

(s/def ::message (s/keys :opt-un [::session ::id ::status ::client-name ::client-version
                                  ::new-session
                                  ::op ::code ::out ::value ::file ::line ::column
                                  ::ex ::root-ex ::ns
                                  ::file-name ::file-path
                                  ::ops ::verbose? ::versions
                                  ::sessions
                                  ::stdin]))
