;-
;   Copyright (c) Meikel Brandmeyer. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns clojure.tools.nrepl.bencode-test
  (:import
    java.io.ByteArrayInputStream
    java.io.ByteArrayOutputStream
    java.io.PushbackInputStream)
  (:use
    [clojure.test :only [deftest is are]]
    clojure.tools.nrepl.bencode))

(defn #^{:private true} >bytes
  [#^String input]
  (.getBytes input "UTF-8"))

(defn #^{:private true} <bytes
  [#^"[B" input]
  (String. input "UTF-8"))

(defn #^{:private true} >input
  [^String input & {:keys [reader]}]
  (-> input
    (.getBytes "UTF-8")
    ByteArrayInputStream.
    PushbackInputStream.
    reader))

(deftest test-netstring-reading
  (are [x y] (= (<bytes (>input x :reader read-netstring)) y)
    "0:,"                ""
    "13:Hello, World!,"  "Hello, World!"
    "16:Hällö, Würld!,"  "Hällö, Würld!"
    "25:Здравей, Свят!," "Здравей, Свят!"))

(deftest test-string-reading
  (are [x y] (= (>input x :reader read-bencode) y)
    "0:"                ""
    "13:Hello, World!"  "Hello, World!"
    "16:Hällö, Würld!"  "Hällö, Würld!"
    "25:Здравей, Свят!" "Здравей, Свят!"))

(deftest test-integer-reading
  (are [x y] (= (>input x :reader read-bencode) y)
    "i0e"     0
    "i42e"   42
    "i-42e" -42))

(deftest test-list-reading
  (are [x y] (= (>input x :reader read-bencode) y)
    "le"                    []
    "l6:cheesee"            ["cheese"]
    "l6:cheese3:ham4:eggse" ["cheese" "ham" "eggs"]))

(deftest test-map-reading
  (are [x y] (= (>input x :reader read-bencode) y)
    "de"            {}
    "d3:ham4:eggse" {"ham" "eggs"}))

(deftest test-nested-reading
  (are [x y] (= (>input x :reader read-bencode) y)
    "l6:cheesei42ed3:ham4:eggsee" ["cheese" 42 {"ham" "eggs"}]
    "d6:cheesei42e3:haml4:eggsee" {"cheese" 42 "ham" ["eggs"]}))

(deftest test-bencode-netstring-reading
  (are [x y] (= (<bytes (>input x :reader read-bencode-netstring)) y)
    "0:"                ""
    "13:Hello, World!"  "Hello, World!"
    "16:Hällö, Würld!"  "Hällö, Würld!"
    "25:Здравей, Свят!" "Здравей, Свят!"))

(defn #^{:private true} >output
  [thing & {:keys [writer]}]
  (let [stream (ByteArrayOutputStream.)]
    (writer stream thing)
    (.toString stream "UTF-8")))

(deftest test-netstring-writing
  (are [x y] (= (>output (>bytes x) :writer write-netstring) y)
    ""               "0:,"
    "Hello, World!"  "13:Hello, World!,"
    "Hällö, Würld!"  "16:Hällö, Würld!,"
    "Здравей, Свят!" "25:Здравей, Свят!,"))

(deftest test-byte-array-writing
  (are [x y] (= (>output (>bytes x) :writer write-bencode) y)
    ""               "0:"
    "Hello, World!"  "13:Hello, World!"
    "Hällö, Würld!"  "16:Hällö, Würld!"
    "Здравей, Свят!" "25:Здравей, Свят!"))

(deftest test-string-writing
  (are [x y] (= (>output x :writer write-bencode) y)
    ""               "0:"
    "Hello, World!"  "13:Hello, World!"
    "Hällö, Würld!"  "16:Hällö, Würld!"
    "Здравей, Свят!" "25:Здравей, Свят!"))

(deftest test-input-stream-writing
  (are [x y] (= (>output (ByteArrayInputStream. (>bytes x))
                         :writer write-bencode) y)
    ""               "0:"
    "Hello, World!"  "13:Hello, World!"
    "Hällö, Würld!"  "16:Hällö, Würld!"
    "Здравей, Свят!" "25:Здравей, Свят!"))

(deftest test-integer-writing
  (are [x y] (= (>output x :writer write-bencode) y)
      0 "i0e"
     42 "i42e"
    -42 "i-42e"

    ; Works for all integral types.
    ; Note: BigInts (42N) not tested, since they are not
    ; supported in 1.2.
    (Byte. "42")    "i42e"
    (Short. "42")   "i42e"
    (Integer. "42") "i42e"
    (Long. "42")    "i42e"))

(deftest test-named-writing
  (are [x y] (= (>output x :writer write-bencode) y)
    :foo      "3:foo"
    :foo/bar  "7:foo/bar"
    'foo      "3:foo"
    'foo/bar  "7:foo/bar"))

(deftest test-list-writing
  (are [x y] (= (>output x :writer write-bencode) y)
    []                      "le"
    ["cheese"]              "l6:cheesee"
    ["cheese" "ham" "eggs"] "l6:cheese3:ham4:eggse"))

(deftest test-map-writing
  (are [x y] (= (>output x :writer write-bencode) y)
    {}             "de"
    {"ham" "eggs"} "d3:ham4:eggse"))

(deftest test-nested-writing
  (are [x y] (= (>output x :writer write-bencode) y)
    ["cheese" 42 {"ham" "eggs"}] "l6:cheesei42ed3:ham4:eggsee"
    {"cheese" 42 "ham" ["eggs"]} "d6:cheesei42e3:haml4:eggsee"))

(deftest test-bencode-netstring-writing
  (are [x y] (= (>output (>bytes x) :writer write-bencode-netstring) y)
    ""               "0:"
    "Hello, World!"  "13:Hello, World!"
    "Hällö, Würld!"  "16:Hällö, Würld!"
    "Здравей, Свят!" "25:Здравей, Свят!"))

(deftest test-lexicographic-sorting
  (let [source   ["ham" "eggs" "hamburg" "hamburger" "cheese"]
        expected ["cheese" "eggs" "ham" "hamburg" "hamburger"]
        to-test  (->> source
                   (map >bytes)
                   (sort @#'clojure.tools.nrepl.bencode/lexicographically)
                   (map <bytes))]
    (is (= to-test expected))))
