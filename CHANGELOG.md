## Changelog

`0.2.7`:

* The topological sort ("linearization") applied to middleware provided to start a new nREPL server has been
  reworked to address certain edge case bugs (NREPL-53)
* `interruptible-eval` no longer incorrectly clobbers a session's `*ns*` binding
  when it processes an `eval` message containing an `ns` "argument"
* Eliminated miscellaneous reflection warnings

`0.2.5`:

* Clients can now signal EOF on `*in*` with an empty `:stdin` value (NREPL-65)
* Clojure `:version-string` is now included in response to a `describe`
  operation (NREPL-63)
* Improve representation of `java.version` information in response to a
  `describe` operation (NREPL-62)

`0.2.4`:

* Fixed the source of a reliable per-connection thread leak (NREPL-40)
* Fix printing of lazy sequences so that `*out*` bindings are properly preserved
  (NREPL-45)
* Enhance `clojure.tools.nrepl.middleware.interruptible-eval/evaluate` so that a
  custom `eval` function can be provided on a per-message basis (NREPL-50)
* Fix pretty-printing of reference returned by
  `clojure.tools.nrepl.server/start-server` (NREPL-51)
* nREPL now works with JDK 1.8 (NREPL-56)
* The value of the `java.version` system property is now included in the response
  to a `describe` operation (NREPL-57)
* Common session bindings (e.g. `*e`, `*1`, etc) are now set in time for nREPL
  middleware to access them in the case of an exception being thrown (NREPL-58)

`0.2.3`:

* Now using a queue to maintain `*in*`, to avoid intermittent failures due to
  prior use of `PipedReader`/`Writer`. (NREPL-39)
* When loading a file, always bind `*print-level*` and `*print-length*` when
  generating the `clojure.lang.Compiler/load` expression (NREPL-41)

`0.2.2`:

* Added `clojure.tools.nrepl/code*` for `pr-str`'ing expressions (presumably
  for later evaluation)
* session IDs are now properly combined into a set by
  `clojure.tools.nrepl/combine-responses`
* fixes printing of server instances under Clojure 1.3.0+ (nREPL-37)

`0.2.1`:

* fixes incorrect translation between `Writer.write()` and
  `StringBuilder.append()` APIs (NREPL-38)

`0.2.0`:

Top-to-bottom redesign

`0.0.6`:

Never released; initial prototype of "rich content" support that (in part)
helped motivate a re-examination of the underlying protocol and design.

`0.0.5`:

- added Clojure 1.3.0 (ALPHA) compatibility

`0.0.4`:

- fixed (hacked) obtaining `clojure.test` output when `clojure.test` is
  initially loaded within an nREPL session
- eliminated 1-minute default timeout on expression evaluation
- all standard REPL var bindings are now properly established and maintained
  within a session
