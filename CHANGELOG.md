# Changelog

## master (unreleased)

## 1.5.1 (2025-10-18)

### Bugs fixed

* [#398](https://github.com/nrepl/nrepl/pull/398): Fix crashes when printing strings with multicodepoint characters.

## 1.5.0 (2025-10-15)

### New features

* [#393](https://github.com/nrepl/nrepl/pull/393): Add `forward-system-output` op for forwarding System/out and System/err output to the client.
* [#383](https://github.com/nrepl/nrepl/pull/383): Introduce `safe-handle` helper to simplify dealing with errors in middleware responses.
* [#386](https://github.com/nrepl/nrepl/pull/386): Add support for `XDG_CONFIG_HOME`.

### Changes

* [#391](https://github.com/nrepl/nrepl/pull/391): **(Breaking)** Remove `nrepl.helpers/load-file-command`.
* [#391](https://github.com/nrepl/nrepl/pull/391): Make `load-file` work completely through `interruptible-eval` middleware.
* [#385](https://github.com/nrepl/nrepl/pull/385): Preserve filename in functions compiled during regular eval.
* [#395](https://github.com/nrepl/nrepl/pull/395): Raise minimal [junixsocket](https://github.com/kohlschutter/junixsocket) version to 2.4.0 (only on pre-JDK17 if you need binding nREPL to UNIX sockets).

### Bugs fixed

* [#215](https://github.com/nrepl/nrepl/pull/215): Don't send `:done` twice if namespace can't be resolved during `eval`.
* [#387](https://github.com/nrepl/nrepl/pull/387): Correctly resolve namespaced keywords in tty transport.

## 1.4.0 (2025-09-02)

### New features

* [#370](https://github.com/nrepl/nrepl/pull/370): Accept `:client-name` and `:client-version` in `clone` op.
* [#374](https://github.com/nrepl/nrepl/pull/374): Add support for dynamic var defaults.

### Changes

* [#378](https://github.com/nrepl/nrepl/pull/378): **(Breaking)** Raise minimal supported Clojure version to 1.8.
* [#375](https://github.com/nrepl/nrepl/pull/375): Refactor and simplify `load-file` middleware.

### Bugs fixed

* [#377](https://github.com/nrepl/nrepl/pull/377): Resolve dynamic variables in middleware from a user session instead of server context.

## 1.3.1 (2025-01-01)

### Bugs fixed

* [#363](https://github.com/nrepl/nrepl/pull/363): Pass the up-to-date `msg` to interruptible eval.
* [#364](https://github.com/nrepl/nrepl/pull/364): Retain explicitly added session values.

## 1.3.0 (2024-08-13)

### Changes

* [#335](https://github.com/nrepl/nrepl/pull/335): Remove support for sideloading and `wrap-sideloader` middleware.
* [#339](https://github.com/nrepl/nrepl/pull/339): Introduce custom REPL implementation instead `clojure.main/repl`.
* [#341](https://github.com/nrepl/nrepl/pull/341): Make `session` middleware handle all dynamic bindings.
* [#342](https://github.com/nrepl/nrepl/pull/342): Make the stack of the `eval` handler shorter. (this makes stacktraces easier to understand)
* [#345](https://github.com/nrepl/nrepl/pull/345): Use customized executors for all asynchronous tasks.
* [#347](https://github.com/nrepl/nrepl/pull/347): Refactor `print` middleware to have tidier stack and use Java classes instead of proxies.

### Bugs fixed

* [#271](https://github.com/nrepl/nrepl/pull/271): Fix not being able to define dynamic variables from terminal REPL.
* [#348](https://github.com/nrepl/nrepl/pull/348): Fail with helpful error if incorrect bencode is written through the transport.
* [#356](https://github.com/nrepl/nrepl/pull/347): The built-in client now prints all output.

## 1.2.0 (2024-06-10)

### Changes

* [#318](https://github.com/nrepl/nrepl/pull/318): Introduce custom JVMTI agent to restore `Thread.stop()` (needed by the `interrupt` op) on JDK20+.
  - [#326](https://github.com/nrepl/nrepl/pull/326): Add explicit opt-out for `libnrepl` agent.
* [#323](https://github.com/nrepl/nrepl/pull/323): Rewrite `nrepl.bencode` implementation to be more performant and use Clojure 1.7 features.

### Bugs fixed

* [#327](https://github.com/nrepl/nrepl/issues/327): Prevent classloader chain from growing after each eval.

## 1.1.2 (2024-05-22)

### Changes

* [#317](https://github.com/nrepl/nrepl/pull/317): Update vendored `compliment-lite` to 0.5.5 ([changelog](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#055-2024-05-06)).

### Bugs fixed

* [#299](https://github.com/nrepl/nrepl/pull/299): Fix `ClassCastException` on re-connect to Unix socket.

## 1.1.1 (2024-02-20)

### Bugs fixed

* [#307](https://github.com/nrepl/nrepl/pull/307): Fix issue where TLS accept loop could sometimes exit prematurely. This caused tests to hang sometimes.
* [#311](https://github.com/nrepl/nrepl/pull/311): Make `--interactive` option work when starting a server on a filesystem socket with `--socket PATH`.

### Changes

* [#304](https://github.com/nrepl/nrepl/pull/304): Improve `completions` op by switching internally to `compliment-lite`. The change is mostly transparent, but should result in more accurate completion results.

## 1.1.0 (2023-11-01)

### New Features

* [#266](https://github.com/nrepl/nrepl/pull/266): Add TLS support.

### Bugs fixed

* [#291](https://github.com/nrepl/nrepl/pull/291): Fix issue with completion middleware not returning values for local class files, or `.jar` files on Windows.

## 1.0.0 (2022-08-24)

### New Features

* [#217](https://github.com/nrepl/nrepl/pull/270): Add nREPL client support for unix domain sockets.

### Changes

* [#279](https://github.com/nrepl/nrepl/pull/279): Allow reader conditionals for tty transport.
* [#281](https://github.com/nrepl/nrepl/pull/281): Make unix domain socket integration compatible with [junixsocket](https://kohlschutter.github.io/junixsocket/) versions >= 2.5.0.

## 0.9.0 (2021-12-12)

### New features

* [#217](https://github.com/nrepl/nrepl/pull/217): Add keyword completion support.
* [#226](https://github.com/nrepl/nrepl/pull/226): Add doc and arglists to completion responses.
* [#238](https://github.com/nrepl/nrepl/pull/238): Expand completion and lookup error message when ns not found.
* [#204](https://github.com/nrepl/nrepl/pull/204): Add server support
  for UNIX domain (filesystem) sockets via `-s/--socket PATH` on the
  command line or `(start-server ... :socket PATH)` whenever the JDK
  is version 16 or newer or
  [junixsocket](https://kohlschutter.github.io/junixsocket/) is
  available as a dependency.
* [#243](https://github.com/nrepl/nrepl/pull/243): Keep the sideloader state in the session so it persists across middleware changes. Sanitize the input in `base64-decode`.

### Bugs fixed

* [#227](https://github.com/nrepl/nrepl/pull/227): Fix completion for static class members.
* [#231](https://github.com/nrepl/nrepl/issues/231): Fix sanitize error when file is `java.net.URL`.
* [#208](https://github.com/nrepl/nrepl/issues/208): Fix namespace resolution in the cmdline REPL.
* [#248](https://github.com/nrepl/nrepl/pull/248): Create fewer new classloaders.
* [#258](https://github.com/nrepl/nrepl/pull/258): Make compatible with graalvm native image.

## 0.8.3 (2020-10-25)

### Bugs fixed

* [#213](https://github.com/nrepl/nrepl/pull/213): Fix sideloader race condition.

## 0.8.2 (2020-09-15)

### Bugs fixed

* [#211](https://github.com/nrepl/nrepl/pull/211): Fix a couple of lookup op errors.

## 0.8.1 (2020-09-03)

### Bugs fixed

* [#206](https://github.com/nrepl/nrepl/issues/206): Fix classloader-related breakage with `cider-nrepl` and Java 8.

## 0.8.0 (2020-08-01)

### New features

* Bundle a couple of print functions compatible with the print middleware (see `nrepl.util.print`).
* [#174](https://github.com/nrepl/nrepl/issues/174): Provide a built-in `completions` op.
* [#143](https://github.com/nrepl/nrepl/issues/143): Added a middleware that allows dynamic loading/unloading of middleware while the server is running.
* [#180](https://github.com/nrepl/nrepl/issues/180): Provide a built-in `lookup` op.

### Bugs fixed

* [#125](https://github.com/nrepl/nrepl/issues/125): The built-in client supports `greeting-fn`.
* [#126](https://github.com/nrepl/nrepl/issues/126): The built-in client exits with an error message when the tty transport is selected. It used to fail silently. This was never supported.
* [#113](https://github.com/nrepl/nrepl/issues/113): Fix an issue with hotloading using Pomegranate in Leiningen.
* [#17](https://github.com/nrepl/nrepl/issues/17): It was possible for the bencode transport to write partial messages if a middleware tries to write something unencodable. This could cause the client or server to hang.

## 0.7.0 (2020-03-28)

### New features

* [#60](https://github.com/nrepl/nrepl/issues/60): Implemented EDN transport.
* [#140](https://github.com/nrepl/nrepl/issues/140): Added initial version of spec for message responses. These are used during Clojure 1.10 tests.
* [#97](https://github.com/nrepl/nrepl/issues/97): Added a sideloader, a network classloader that allows dependencies to be added even when the source/class files are not available on the server JVM's classpath (e.g. supplied by the client).

### Bugs fixed

* [#152](https://github.com/nrepl/nrepl/issues/152): Kill session threads when closing sessions.
* [#132](https://github.com/nrepl/nrepl/issues/132): Avoid malformed bencode messages during interrupts, mainly affecting streamed printing.

### Changes

* [#137](https://github.com/nrepl/nrepl/pull/137): Expanded Bencode writer to work with
  maps that have keywords or symbols as keys. This allowed a simplification of the
  Bencode transport itself.
* [#158](https://github.com/nrepl/nrepl/issues/158): Interrupt now runs in three stages: calls `interrupt` on the thread, waits 100ms for the thread to respond and return messages, then waits 5000ms for the thread to terminate itself. A hard `.stop` is only called if it fails to do so.
* [#178](https://github.com/nrepl/nrepl/pull/178): Allow `:read-cond` option when evaluating code.
* [#167](https://github.com/nrepl/nrepl/issues/167): Allow suppressing ack message when using `nrepl.cmdline`.

## 0.6.0 (2019-02-05)

### New features

* [#117](https://github.com/nrepl/nrepl/issues/117): Replace
  `nrepl.middleware.pr-values` with `nrepl.middleware.print`.
  * New dynamic vars in `nrepl.middleware.print` for configuring the print
    middleware at the REPL.
  * The new middleware provides behaviour that is backwards-compatible with the
    old one. Existing middleware descriptors whose `:requires` set contains
    `#'pr-values` should instead use `#'wrap-print`.
* [#128](https://github.com/nrepl/nrepl/pull/128): New middleware,
  `nrepl.middleware.caught`, provides a hook called when eval, read, or print
  throws an exception or error. Defaults to `clojure.main/repl-caught`.
  Configurable by the dynamic var `nrepl.middleware.caught/*caught-fn*`.

### Bugs fixed

* [CLI] Make sure ack port parameter is converted to integer for command line nREPL initialization.
* [CLI] When starting the REPL, make sure the transport option is used correctly.
* [CLI] Make sure calling `send-ack` at `cmdline` ns works with the correct transport.
* [#8](https://github.com/nrepl/nrepl/issues/8): Clean up context classloader after eval.

### Changes

* [#16](https://github.com/nrepl/nrepl/issues/16): Use a single session thread per evaluation.
* [#107](https://github.com/nrepl/nrepl/issues/107): Stop reading and evaluating code on first read error.
* [#108](https://github.com/nrepl/nrepl/issues/108): Refactor cmdline functions into a public, reusable API.
* Restore the `nrepl.bencode` namespace.

## 0.5.3 (2018-12-12)

### Bugs fixed

* Make sure we never send a nil transport to via `send-ack`.

## 0.5.2 (2018-12-10)

### Bugs fixed

* [CLI] [#90](https://github.com/nrepl/nrepl/issues/90): Doesn't display properly URLs if using a 3rd-party transport.

## 0.5.1 (2018-11-30)

### Changes

* [#89](https://github.com/nrepl/nrepl/issues/89): Remove `tools.logging` dependency.

## 0.5.0 (2018-11-28)

### New features

* [#12](https://github.com/nrepl/nrepl/issues/12): Support custom printing
  function in `pr-values`, enabling pretty-printed REPL results.
* [#66](https://github.com/nrepl/nrepl/pull/66): Add support for a global and local configuration file.
* [CLI] [#63](https://github.com/nrepl/nrepl/issues/63): Make it possible to specify the transport via the command-line client (`--transport/-t`).

### Bugs fixed

* [#10](https://github.com/nrepl/nrepl/issues/10): Bind `*1`, `*2`, `*3` and `*e` in cloned session.
* [#33](https://github.com/nrepl/nrepl/issues/33): Add ability to change value of `*print-namespace-maps*`.
* [#68](https://github.com/nrepl/nrepl/issues/68): Avoid illegal access warning on JDK 9+ caused by `nrepl.middleware.interruptible-eval/set-line!`.
* [CLI] [#77](https://github.com/nrepl/nrepl/issues/77): Exit cleanly after pressing `ctrl-d` in an interactive REPL.
* [#13](https://github.com/nrepl/nrepl/issues/13): Catch `ThreadDeath` exception thrown by interrupt.

### Changes

* [#56](https://github.com/nrepl/nrepl/issues/56): Bind the server by default to `127.0.0.1` instead of to `::` (this turned out to be a security risk).
* [#76](https://github.com/nrepl/nrepl/pull/76): Move version-related logic to a dedicated namespace (`nrepl.version`).
* Deprecate `nrepl.core/version`.
* Deprecate `nrepl.core/version-string`.
* [CLI] [#81](https://github.com/nrepl/nrepl/pull/81): Handle interrupt in interactive session.

## 0.4.5 (2018-09-02)

### New features

* [CLI] The built-in the CLI generates an `.nrepl-port` file on server startup.
* [CLI] [#39](https://github.com/nrepl/nrepl/issues/39): Add a `--connect` command-line option allowing you to connect.
with the built-in client to an already running nREPL server.
* [CLI] Add shorthand names for most command-line options.
* [CLI] Add a `-v/--version` command-line option.

### Changes

* [#32](https://github.com/nrepl/nrepl/issues/32): Extract the bencode logic in a [separate library](https://github.com/nrepl/bencode).

### Bugs fixed

* [#38](https://github.com/nrepl/nrepl/issues/38): Remove extra newline in REPL output.

## 0.4.4 (2018-07-31)

### New features

* [CLI] Added `--help` command-line option.
* [CLI] Added `--bind` command-line option.
* [CLI] Added `--handler` and `--middleware` command-line options. Extremely useful when starting nREPL using
`clj` and `tools.deps`, as this allows you to inject middleware trivially without the need for
something like `lein` or `boot`.

### Bugs fixed

* [CLI] Add missing newline after colorized values displayed in the REPL.

## 0.4.3 (2018-07-26)

### New features

* [CLI] Display connection info when starting the built-in cmd client. This makes it possible
for clients like CIDER to parse it and auto-connect to the server. Pretty handy if you're
using `clj` to start your server.

### Bugs fixed

* [#16](https://github.com/nrepl/nrepl/issues/16): Don't change the
  thread used for form evaluation over time. See
  [#36](https://github.com/nrepl/nrepl/pull/36) for a discussion of
  the fix. **(partial fix)**

### Changes

* The result of `nrepl.server/start-server` no longer contains the
legacy key `:ss` from the days of nREPL 0.0.x. If someone was using it
they should switch to `:server-socket` instead.
* [#28](https://github.com/nrepl/nrepl/issues/28): Echo back missing
ns during eval (previously you'd only get an error that a ns is missing,
but no mention of the name of that namespace).

## 0.4.2 (2018-07-18)

### Changes

* [#35](https://github.com/nrepl/nrepl/pull/35): Add constant DCL
across evaluations (which means you can now easily hot-load
dependencies).

### Bugs fixed

* [#34](https://github.com/nrepl/nrepl/pull/34): Treat `nil` port as 0
  (which assigns a random port).

## 0.4.1 (2018-05-23)

### Bugs fixed

* [#11](https://github.com/nrepl/nrepl/issues/11): Don't read the version string
from a resource file (`version.txt`).

## 0.4.0 (2018-05-21)

### Changes

* [#4](https://github.com/nrepl/nrepl/issues/4): (**Breaking**) Change the project's
  namespaces. `clojure.tools.nrepl` is now `nrepl.core`,
  the rest of the namespaces were renamed following the pattern
  `clojure.tools.nrepl.*` -> `nrepl.*`.

## 0.3.1 (2018-05-19)

### Bugs fixed

* [#15](https://github.com/nrepl/nrepl/issues/15) Fix for
  `clojure.tools.nrepl.middleware.session` for `:unknown-session`
  error and `clojure.tools.nrepl.middleware.interruptible-eval` for
  `:no-code` error, the correct response of `:status :done` is now
  being returned.
* [#26](https://github.com/nrepl/nrepl/issues/26): Recompile the Java classes
for Java 8.

## 0.3.0 (2018-05-18)

### Changes

* [#1](https://github.com/nrepl/nrepl/issues/1): Materially identical
  to `[org.clojure/tools.nrepl "0.2.13"]`, but released under
  `nrepl/nrepl` coordinates as part of the migration out of
  clojure-contrib https://github.com/nrepl/nrepl
* `clojure.tools/logging` is now a normal dependency (it used to be an
  optional dependency).

### Bugs fixed

* [#20](https://github.com/nrepl/nrepl/issues/20): If `start-server` is not provided with a `:bind` hostname, nREPL will default
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
