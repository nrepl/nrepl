(ns nrepl.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; General

(s/def ::uuid-str #(and (string? %)
                        (re-matches #"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
                                    (str/lower-case %))))

;; should be uuid strings
(s/def ::session (s/or :single ::uuid-str
                       :multi  (s/coll-of ::uuid-str :kind? set?)))

;; Clone

(s/def ::new-session string?)

;; Describe

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

;; Eval

(s/def ::op string?)

(s/def ::code string?)

(s/def ::out string?)

(s/def ::value any?)

(s/def ::line int?)

(s/def ::column int?)

(s/def ::file string?)

(s/def ::id string?)

(s/def ::ex string?)

(s/def ::root-ex string?)

(s/def ::ns string?)

;; TODO spec the print inputs/options

;; TODO spec the caught inputs/output

;; Load File

(s/def ::file-name string?)

(s/def ::file-path string?)

(s/def ::status (s/or :single keyword?
                      :multi  (s/coll-of keyword? :kind? set?)))

;; List Sessions

(s/def ::sessions (s/coll-of ::uuid-str :kind? set?))

;; Std In

(s/def ::stdin string?)

;; Message map def

(s/def ::message (s/keys :opt-un [::session ::id ::status
                                  ::new-session
                                  ::op ::code ::out ::value ::file ::line ::column
                                  ::ex ::root-ex ::ns
                                  ::file-name ::file-path
                                  ::ops ::verbose? ::versions
                                  ::sessions
                                  ::stdin]))
