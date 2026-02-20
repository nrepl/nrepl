;; Copyright (c) Meikel Brandmeyer, Oleksandr Yakushev. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns nrepl.bencode
  "A netstring and bencode implementation for Clojure."
  {:author "Meikel Brandmeyer"}
  (:require
   [clojure.java.io :as io])
  (:import
   (clojure.lang IPersistentCollection IPersistentMap Named PersistentVector)
   (java.io ByteArrayOutputStream EOFException InputStream IOException
            OutputStream PushbackInputStream)
   (java.nio.charset StandardCharsets)
   (java.util Arrays)))

;; # Motivation
;;
;; In each and every application, which contacts peer processes via some
;; communication channel, the handling of the communication channel is
;; obviously a central part of the application. Unfortunately introduces
;; handling of buffers of varying sizes often bugs in form of buffer
;; overflows and similar.
;;
;; A strong factor in this situation is of course the protocol which goes
;; over the wire. Depending on its design it might be difficult to estimate
;; the size of the input up front. This introduces more handling of message
;; buffers to accommodate for inputs of varying sizes. This is particularly
;; difficult in languages like C, where there is no bounds checking of array
;; accesses and where errors might go unnoticed for considerable amount of
;; time.
;;
;; To address these issues D. Bernstein developed the so called
;; [netstrings][net]. They are especially designed to allow easy construction
;; of the message buffers, easy and robust parsing.
;;
;; BitTorrent extended this to the [bencode][bc] protocol which also
;; includes ways to encode numbers and collections like lists or maps.
;;
;; *wire* is based on these ideas.
;;
;; [net]: http://cr.yp.to/proto/netstrings.txt
;; [bc]:  http://wiki.theory.org/BitTorrentSpecification#Bencoding
;;
;; # Netstrings
;;
;; Now let's start with the basic netstrings. They consist of a byte count,
;; followed by a colon and the binary data and a trailing comma. Examples:
;;
;;     13:Hello, World!,
;;     10:Guten Tag!,
;;     0:,
;;
;; The initial byte count allows to efficiently allocate a sufficiently
;; sized message buffer. The trailing comma serves as a hint to detect
;; incorrect netstrings.
;;
;; ## Low-level reading
;;
;; We will need some low-level reading helpers to read the bytes from
;; the input stream. These are `read-byte` as well as `read-bytes`. They
;; are split out, because doing such a simple task as reading a byte is
;; mild catastrophe in Java. So it would add some clutter to the algorithm
;; `read-netstring`.
;;
;; On the other hand they might be also useful elsewhere.
;;
;; To remove some magic numbers from the code below.

(def ^:const i     105)
(def ^:const l     108)
(def ^:const d     100)
(def ^:const e     101)
(def ^:const comma 44)
(def ^:const minus 45)
(def ^:const colon 58)

(defn- throw-eof []
  (throw (EOFException. "Invalid netstring. Unexpected end of input.")))

;; This function is inline because it is used in a hot loop inside `read-long`.

(definline ^:private read-byte [input]
  ;; There is a quirk here. `.read` returns -1 on end of input. However, the
  ;; Java `byte` has a range from -128 to 127. To accommodate for that, Java
  ;; uses `Byte/toUnsignedInt` which offsets the byte value by 256. The result
  ;; is an `int` that has a range 0-255. Everything below the value 128 stands
  ;; for itself. But larger values are actually negative byte values. We have to
  ;; translate it back here. Narrowing downcast to byte does precisely that.
  `(let [c# (.read ~(with-meta input {:tag 'InputStream}))]
     (when (neg? c#) (throw-eof))
     ;; Cast back to int to avoid boxing and/or redundant casts for consumers.
     (unchecked-int (unchecked-byte c#))))

(defn- read-bytes ^bytes [^InputStream input, ^long n]
  (let [content (byte-array n)]
    (loop [offset 0, len n]
      (let [result (.read input content (unchecked-int offset)
                          (unchecked-int len))]
        (when (neg? result)
          (throw
           (EOFException.
            "Invalid netstring. Less data available than expected.")))
        (when-not (= result len)
          (recur (unchecked-add offset result) (unchecked-subtract len result)))))
    content))

;; `read-long` is used for reading integers from the stream as well
;; as the byte count prefixes of byte strings. The delimiter is \:
;; for byte count prefixes and \e for integers.

(defn- read-long ^long [^PushbackInputStream input, ^long delim]
  (let [first-byte (read-byte input)
        ;; Only the first byte can be a minus.
        negate? (if (= first-byte minus)
                  true
                  (do (.unread input first-byte)
                      false))]
    (loop [n 0]
      ;; We read repeatedly a byte from the input...
      (let [b (read-byte input)]
        ;; ...and stop at the delimiter.
        (if (= b delim)
          (if negate? (- n) n)
          (recur (+ (* n 10) (- b 48))))))))

;; ## Reading a netstring
;;
;; Let's dive straight into reading a netstring from an `InputStream`.
;;
;; For convenience we split the function into two subfunctions. The
;; public `read-netstring` is the normal entry point, which also checks
;; for the trailing comma after reading the payload data with the
;; private `read-netstring*`.
;;
;; The reason we need the less strict `read-netstring*` is that with
;; bencode we don't have a trailing comma. So a check would not be
;; beneficial here.
;;
;; However the consumer doesn't have to care. `read-netstring` as
;; well as `read-bencode` provide the public entry points, which do
;; the right thing. Although they both may reference the `read-netstring*`
;; underneath.
;;
;; With this in mind we define the inner helper function first.

(defn- read-netstring* [input]
  (read-bytes input (read-long input colon)))

;; And the public facing API: `read-netstring`.

(defn read-netstring
  "Reads a classic netstring from input—an InputStream. Returns the
  contained binary data as byte array."
  ^bytes [input]
  (let [content (read-netstring* input)]
    (when-not (= (read-byte input) comma)
      (throw (IOException. "Invalid netstring. ',' expected.")))
    content))

;; Similarly the `string>payload` and `string<payload` functions
;; are defined as follows to simplify the conversion between strings
;; and byte arrays in various parts of the code.

(defn- string>payload ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- string<payload ^String [^bytes b]
  (String. b StandardCharsets/UTF_8))

;; ## Writing a netstring
;;
;; This opposite operation – writing a netstring – is just as important.
;;
;; *Note:* We take here a byte array, just as we returned a byte
;; array in `read-netstring`. The netstring should not be concerned
;; about the actual contents. It just sees binary data.
;;
;; Similar to `read-netstring` we also split `write-netstring` into
;; the entry point itself and a helper function.

(defn- write-netstring* [^OutputStream output, ^bytes content]
  (doto output
    (.write (string>payload (str (alength content))))
    (.write (unchecked-int colon))
    (.write content)))

(defn write-netstring
  "Write the given binary data to the output stream in form of a classic
  netstring."
  [^OutputStream output, content]
  (doto output
    (write-netstring* content)
    (.write (unchecked-int comma))))

;; # Bencode
;;
;; However most of the time we don't want to send simple blobs of data
;; back and forth. The data sent between the communication peers usually
;; have some structure, which has to be carried along the way to the
;; other side. Here [bencode][bc] come into play.
;;
;; Bencode defines additionally to netstrings easily parseable structures
;; for lists, maps and numbers. It allows to communicate information
;; about the data structure to the peer on the other side.
;;
;; ## Tokens
;;
;; The data is encoded in tokens in bencode. There are several types of
;; tokens:
;;
;;  * A netstring without trailing comma for string data.
;;  * A tag specifying the type of the following tokens.
;;    The tag may be one of these:
;;     * `\i` to encode integers.
;;     * `\l` to encode lists of items.
;;     * `\d` to encode maps of item pairs.
;;  * `\e` to end a previously started tag.
;;
;; ## Reading bencode
;;
;; Reading bencode encoded data is basically parsing a stream of values from the
;; input. To read the bencode encoded data we walk a long the sequence of values
;; and act according to the found tags.

(declare read-bencode)

;; Integers consist of a sequence of decimal digits.

(defn- read-integer [input]
  (read-long input e))

;; Lists are just a sequence of other tokens.

(defn- read-list [input]
  (loop [res (transient [])]
    (if-some [val (read-bencode input)]
      (recur (conj! res val))
      (persistent! res))))

;; Maps are sequences of key/value pairs. The keys are always
;; decoded into strings. The values are kept as is.

(defn- read-map [input]
  (loop [m (transient {})]
    (if-some [key (read-bencode input)]
      (if-some [val (read-bencode input)]
        (recur (assoc! m (string<payload key) val))
        (throw (EOFException. "Invalid bencode map. Unexpected end of input.")))
      (persistent! m))))

;; Note that we use `nil` to represent the terminator read from the input. It is
;; safe because there is no way to encode `nil` into bencode, and thus it is
;; impossible to read `nil` as a legal value out of the stream.

(defn read-bencode
  "Read bencode token from the input stream."
  [^PushbackInputStream input]
  (let [first-byte (read-byte input)]
    (cond (= first-byte i) (read-integer input)
          (= first-byte l) (read-list input)
          (= first-byte d) (read-map input)
          (= first-byte e) nil
          :else (do (.unread input first-byte)
                    (read-netstring* input)))))

(defn read-nrepl-message
  "Same as `read-bencode`, but ensure that the top-level value is a map as
  expected by the nREPL protocol."
  [^PushbackInputStream input]
  (let [first-byte (read-byte input)]
    (if (= first-byte d)
      (read-map input)
      (throw (ex-info (format "nREPL message must be a map.
Wrong first byte: %s (must be %d)." first-byte d) {})))))

;; ## Writing bencode
;;
;; Writing bencode is similarly easy as reading it. The main entry point
;; takes a string, map, sequence or integer and writes it according to
;; the rules to the given OutputStream.

(defprotocol BencodeSerializable
  "Protocol for types that can be serialized to bencode."
  (write-bencode* [object output]))

(defn write-bencode
  "Write the given thing to the output stream. “Thing” means here a
  string, map, sequence or integer. Alternatively an ByteArray may
  be provided whose contents are written as a bytestring. Similar
  the contents of a given InputStream are written as a byte string.
  Named things (symbols or keywords) are written in the form
  'namespace/name'."
  [output thing]
  (write-bencode* thing output))

(defn- throw-illegal-value [obj what]
  (throw (IllegalArgumentException.
          (format "Cannot write %s of type %s" what (class obj)))))

;; Lists and maps work recursively to print their elements.

(defn- write-bencode-list [lst, ^OutputStream output]
  (.write output (unchecked-int l))
  (run! #(write-bencode output %) lst)
  (.write output (unchecked-int e)))

;; However, maps are special because their keys are sorted lexicographically
;; based on their byte string representation.

(defn- named>string [named]
  (cond (string? named) named
        (symbol? named) (str named)
        (keyword? named) (str (.sym ^clojure.lang.Keyword named))
        :else (throw-illegal-value named "map key")))

(defn- write-bencode-map [^IPersistentMap m, ^OutputStream output]
  ;; The implementation here is quite unidiomatic for performance reasons. We
  ;; need to transform the keys of a map to strings, then sort the kvs
  ;; lexicographically by key, then write to output. To avoid creating redundant
  ;; data structures, we use array as the transitional structure that keeps
  ;; stringified kvs that we sort and in the end efficiently foreach.
  (let [n (.count m) ;; Because `clojure.core/count` is quite slow.
        arr (object-array n)]
    ;; Using reduce-kv as an efficient map iterator for side effects.
    (reduce-kv (fn [i k v]
                 (aset arr i [(named>string k) v])
                 (inc i))
               0 m)

    (Arrays/sort arr (fn [^PersistentVector x, ^PersistentVector y]
                       (compare (.nth x 0) (.nth y 0))))

    (.write output (unchecked-int d))
    (dotimes [i n]
      (let [^PersistentVector kv (aget arr i)]
        (write-netstring* output (string>payload (.nth kv 0)))
        (write-bencode output (.nth kv 1))))
    (.write output (unchecked-int e))))

(extend-protocol BencodeSerializable
  nil
  (write-bencode* [_ output]
    ;; Treat nil as an empty list.
    (write-bencode* [] output))

  InputStream
  (write-bencode* [stream output]
    ;; Streaming does not really work, since we need to know the number of bytes
    ;; to write upfront. So we read in everything for InputStreams and pass on
    ;; the byte array.
    (let [bytes (ByteArrayOutputStream.)]
      (io/copy stream bytes)
      (write-netstring* output (.toByteArray bytes))))

  IPersistentMap
  (write-bencode* [m output] (write-bencode-map m output))

  IPersistentCollection
  (write-bencode* [coll output] (write-bencode-list coll output))

  Number
  (write-bencode* [n, ^OutputStream output]
    (if (integer? n)
      (doto output
        (.write (unchecked-int i))
        (.write (string>payload (str n)))
        (.write (unchecked-int e)))
      (throw-illegal-value n "value")))

  String
  (write-bencode* [s output]
    ;; For strings we simply write the string as a netstring without trailing
    ;; comma after encoding the string as UTF-8 bytes.
    (write-netstring* output (string>payload s)))

  ;; Symbols and keywords are converted to a string of the form 'namespace/name'
  ;; or just 'name' in case it's not qualified. We do not add colons for keywords
  ;; since the other side might not have the notion of keywords.

  Named
  (write-bencode* [named output] (write-bencode* (named>string named) output))

  Object
  (write-bencode* [o output]
    ;; In the catch-all, check a few more conditions that are not easy to
    ;; declare as separate types.
    (cond
      ;; The easiest case is of course when we already have a byte array.
      ;; We can simply pass it on to the underlying machinery.
      (-> o class .getComponentType (= Byte/TYPE))
      (write-netstring* output o)

      ;; Treat other arrays as lists.
      (.isArray (class o))
      (write-bencode-list o output)

      :else (throw-illegal-value o "value"))))
