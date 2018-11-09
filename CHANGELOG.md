## Changelog

### master (unreleased)

#### New features

* [#12](https://github.com/nrepl/nREPL/issues/12): Support custom rendering
  function in `pr-values`, enabling pretty-printed REPL results.
* [#66](https://github.com/nrepl/nrepl/pull/66): Add support for a global and local configuration file, as well as configuration via env variables.
* [#63](https://github.com/nrepl/nrepl/issues/63): Make it possible to specify the transport via the command-line client (`--transport/-t`).

#### Bugs fixed

* [#10](https://github.com/nrepl/nREPL/issues/10): Bind *1, *2, *3 and *e in cloned session.
* [#33](https://github.com/nrepl/nREPL/issues/33): Add ability to change value of `*print-namespace-maps*`.
* [#68](https://github.com/nrepl/nREPL/issues/68): Avoid illegal access warning caused by set-line!.
* [#77](https://github.com/nrepl/nREPL/issues/77): Exit when entering ctrl-d in interactive session.

#### Changes

* [#56](https://github.com/nrepl/nREPL/issues/56): Bind the server by default to `127.0.0.1` instead of to `::` (this turned out to be a security risk).

### 0.4.5 (2018-09-02)

#### New features

* The built-in the CLI generates an `.nrepl-port` file on server startup.
* [#39](https://github.com/nrepl/nREPL/issues/39): Add a `--connect` command-line option allowing you to connect.
with the built-in client to an already running nREPL server.
* Add shorthand names for most command-line options.
* Add a `-v/--version` command-line option.

#### Changes

* [#32](https://github.com/nrepl/nREPL/issues/32): Extract the bencode logic in a [separate library](https://github.com/nrepl/bencode).

#### Bugs fixed

* [#38](https://github.com/nrepl/nREPL/issues/38): Remove extra newline in REPL output.

### 0.4.4 (2018-07-31)

#### New features

* Added `--help` command-line option.
* Added `--bind` command-line option.
* Added `--handler` and `--middleware` command-line options. Extremely useful when starting nREPL using
`clj` and `tools.deps`, as this allows you to inject middleware trivially without the need for
something like `lein` or `boot`.

#### Bugs fixed

* Add missing newline after colorized values displayed in the REPL.

### 0.4.3 (2018-07-26)

#### New features

* Display connection info when starting the built-in cmd client. This makes it possible
for clients like CIDER to parse it and auto-connect to the server. Pretty handy if you're
using `clj` to start your server.

#### Bugs fixed

* [#16](https://github.com/nrepl/nREPL/issues/16): Don't change the
  thread used for form evaluation over time. See
  [#36](https://github.com/nrepl/nREPL/pull/36) for a discussion of
  the fix. **(partial fix)**

#### Changes

* The result of `nrepl.server/start-server` no longer contains the
legacy key `:ss` from the days of nREPL 0.0.x. If someone was using it
they should switch to `:server-socket` instead.
* [#28](https://github.com/nrepl/nREPL/issues/28): Echo back missing
ns during eval (previously you'd only get an error that a ns is missing,
but no mention of the name of that namespace).

### 0.4.2 (2018-07-18)

#### Changes

* [#35](https://github.com/nrepl/nREPL/pull/35): Add constant DCL
across evaluations (which means you can now easily hot-load
dependencies).

#### Bugs fixed

* [#34](https://github.com/nrepl/nREPL/pull/34): Treat `nil` port as 0
  (which assigns a random port).

### 0.4.1 (2018-05-23)

#### Bugs fixed

* [#11](https://github.com/nrepl/nREPL/issues/11): Don't read the version string
from a resource file (`version.txt`).

### 0.4.0 (2018-05-21)

#### Changes

* [#4](https://github.com/nrepl/nREPL/issues/4): Change the project's
  namespaces. (**breaking**) `clojure.tools.nrepl` is now `nrepl.core`,
  the rest of the namespaces were renamed following the pattern
  `clojure.tools.nrepl.*` -> `nrepl.*`.

### 0.3.1 (2018-05-19)

#### Bugs fixed

* [#15](https://github.com/nrepl/nREPL/issues/15) Fix for
  `clojure.tools.nrepl.middleware.session` for `:unknown-session`
  error and `clojure.tools.nrepl.middleware.interruptible-eval` for
  `:no-code` error, the correct response of `:status :done` is now
  being returned.
* [#26](https://github.com/nrepl/nREPL/issues/26): Recompile the Java classes
for Java 8.

### 0.3.0 (2018-05-18)

#### Changes

* [#1](https://github.com/nrepl/nREPL/issues/1): Materially identical
  to `[org.clojure/tools.nrepl "0.2.13"]`, but released under
  `nrepl/nrepl` coordinates as part of the migration out of
  clojure-contrib https://github.com/nrepl/nREPL
* `clojure.tools/logging` is now a normal dependency (it used to be an
  optional dependency).

#### Bugs fixed

* [#20](https://github.com/nrepl/nREPL/issues/20): If `start-server` is not provided with a `:bind` hostname, nREPL will default
  to binding to the ipv6 `::` (as before), but will now _always_ fall back to
  `localhost`. Previously, the ipv4 hostname was only used if `::` could not be
  resolved; this change ensures that the `localhost` fallback is used in
  networking environments where `::` is resolved successfully, but cannot be
  bound.

--------------------------------------------------------------------------------

`0.2.13`:

* `start-server` now binds to `::` by default, and falls back to `localhost`,
  avoiding confusion when working in environments that have both IPv4 and IPv6
  networking available. (NREPL-83)

`0.2.11`:

* `clojure.tools.nrepl.middleware.interruptible-eval` now accepts optional
  `file`, `line`, and `column` values in order to fix location metadata to
  defined vars and functions, for more useful stack traces, navigation, etc.
* REPL evaluations now support use of reader conditionals (loading `.cljc` files
  containing reader conditionals has always worked transparently)

`0.2.10`:

* `clojure.tools.nrepl.middleware.pr-values` will _not_ print the contents of
  `:value` response messages if the message contains a `:printed-value` slot.
* `default-executor` and `queue-eval` in
  `clojure.tools.nrepl.middleware.interruptible-eval` are now public.

`0.2.9`:

* `clojure.tools.nrepl.middleware.interruptible-eval` now defines a default
  thread executor used for all evaluations (unless a different executor is
  provided to the configuration of
  `clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval`). This
  should aid in the development of `interrupt`-capable alternative evaluation
  middlewares/handlers.

`0.2.8`:

* The default bind address used by `clojure.tools.nrepl.server/start-server` is
  now `localhost`, not `0.0.0.0`. As always, the bind address can be set
  explicitly via a `:bind` keyword argument to that function. This is considered
  a security bugfix, though _technically_ it may cause breakage if anyone was
  implicitly relying upon nREPL's socket server to listen on all network
  interfaces.
* The `ServerSocket` created as part of
  `clojure.tools.nrepl.server/start-server` is now configured with
  `SO_REUSEADDR` enabled; this should prevent spurious "address already in use"
  when quickly bouncing apps that open an nREPL server on a fixed port, etc.
  (NREPL-67)
* Middlewares may now contribute to the response of the `"describe"` operation
  via an optional `:describe-fn` function provided via their descriptors.
  (NREPL-64)
* The `:ns` component of the response to `"load-file"` operations is now elided,
  as it was (usually) incorrect (as a result of reusing `interruptible-eval` for
  handling `load-file` operations) (NREPL-68)

`0.2.7`:

* The topological sort ("linearization") applied to middleware provided to start
  a new nREPL server has been reworked to address certain edge case bugs
  (NREPL-53)
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
