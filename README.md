# nREPL

[nREPL](http://github.com/clojure/tools.nrepl) is a Clojure *n*etwork REPL that
provides a REPL server and client, along with some common APIs
of use to IDEs and other tools that may need to evaluate Clojure
code in remote environments.

## Usage

### "Installation" <a name="installing"/>

nREPL is available in Maven central. Add this `:dependency` to your Leiningen
`project.clj`:

```clojure
[org.clojure/tools.nrepl "0.2.0-beta10"]
```

Or, add this to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>org.clojure</groupId>
  <artifactId>tools.nrepl</artifactId>
  <version>0.2.0-beta10</version>
</dependency>
```

A list of all prior releases are available [here](http://search.maven.org/#search|gav|1|g%3A%22org.clojure%22%20AND%20a%3A%22tools.nrepl%22).

Please note the changelog below.

nREPL is compatible with Clojure 1.2.0 - 1.4.0.

Please post general questions or discussion on either the [clojure-dev](http://groups.google.com/group/clojure-dev/) or [clojure-tools](http://groups.google.com/group/clojure-tools) mailing lists.  Bug reports and such may be filed into [nREPL's JIRA](http://dev.clojure.org/jira/browse/NREPL).

nREPL's generated API documentation is available [here](http://clojure.github.com/tools.nrepl/).
A [history of nREPL builds](http://build.clojure.org/job/tools.nrepl/) is available, as well as
[a compatibility test matrix](http://build.clojure.org/job/tools.nrepl-test-matrix/), verifying
nREPL's functionality against multiple versions of Clojure (v1.2.0+ supported) and multiple
JVMs.

### Connecting to an nREPL server

Most of the time, you will connect to an nREPL server using an existing
client/tool.  Tools that support nREPL include:

* [Leiningen](https://github.com/technomancy/leiningen) (starting with v2)
* [Counterclockwise](http://code.google.com/p/counterclockwise/) (Clojure plugin
  for Eclipse)
* [Reply](https://github.com/trptcolin/reply/)
* [Jark](http://icylisper.in/jark/)

If you want to connect to an nREPL server using the default transport, something
like this will work:

```clojure
=> (require '[clojure.tools.nrepl :as repl])
nil
=> (with-open [conn (repl/connect :port 59258)]
     (-> (repl/client conn 1000)    ; message receive timeout required
       (repl/message {:op :eval :code "(+ 2 3)"})
       repl/response-values))
[5]
```

`response-values` will return only the values of evaluated expressions, read
from their (by default) `pr`-encoded representations via `read`.  You can see
the full content of message responses easily:

```clojure
=> (with-open [conn (repl/connect :port 59258)]
     (-> (repl/client conn 1000)
       (repl/message {:op :eval :code "(time (reduce + (range 1e6)))"})
       doall      ;; `message` and `client-session` all return lazy seqs
       pprint))
nil
({:out "\"Elapsed time: 68.032 msecs\"\n",
  :session "2ba81681-5093-4262-81c5-edddad573201",
  :id "3124d886-7a5d-4c1e-9fc3-2946b1b3cfaa"}
 {:ns "user",
  :value "499999500000",
  :session "2ba81681-5093-4262-81c5-edddad573201",
  :id "3124d886-7a5d-4c1e-9fc3-2946b1b3cfaa"}
 {:status ["done"],
  :session "2ba81681-5093-4262-81c5-edddad573201",
  :id "3124d886-7a5d-4c1e-9fc3-2946b1b3cfaa"})
```

The different message types and their schemas are detailed
[here](#defaultschema). 

### Embedding nREPL, starting a server

If your project uses Leiningen (v2 or higher), you already have access to an
nREPL server for your project via `lein repl`.

Otherwise, it can be extremely useful to have your application host a REPL
server whereever it might be deployed; this can greatly simplify debugging,
sanity-checking, and so on.

nREPL provides a socket-based server that you can trivially start from your
application.  [Add it to your project's dependencies](#installing), and add code
like this to your app:

```clojure
=> (use '[clojure.tools.nrepl.server :only (start-server stop-server)])
nil
=> (defonce server (start-server :port 7888))
#'user/server
```

Depending on what the lifecycle of your application is, whether you want to be
able to easily restart the server, etc., you might want to put the value
`start-server` returns into an atom or somesuch.  Anyway, once your app is
running an nREPL server, you can connect to it from a tool like Leiningen or
Counterclockwise or Reply, or from another Clojure process:

```clojure
=> (with-open [conn (repl/connect :port 7888)]
     (-> (repl/client conn 1000)
       (repl/message {:op :eval :code "(+ 1 1)"})
       repl/response-values))
[2]
```

You can stop the server with `(stop-server server)`.

#### Server options

* tty
* http
* implementing your own transport / handler(s)

### Building nREPL

Releases are available from Maven
Central, and SNAPSHOT builds from master's HEAD are automatically deployed to
Sonatype's OSS repository (see [this](http://dev.clojure.org/display/doc/Maven+Settings+and+Repositories)
for how to configure Leiningen or Maven to use OSS-snapshots), so building nREPL
shouldn't ever be necessary.  That said:

0. Clone the repo
1. Make sure you have maven installed
2. Run the maven build; run either:
    1. `mvn package`: This will produce an nREPL jar file in the `target`
directory, and run all tests against Clojure 1.2.0.
    2. `mvn verify`: This does the same, but also runs the tests with
other Clojure "profiles" (currently v1.1.0 and v1.1.0 + clojure-contrib). 

## Need Help?

Ping `cemerick` on freenode irc or twitter.

## Why nREPL?

nREPL has been designed with the aim of ensuring that it satisfies the
requirements of both application developers (in support of activities ranging
from interactive remote debugging and experimentation in development
contexts through to more advanced use cases such as updating deployed
applications) as well as toolmakers (providing a standard way to connect to and
introspect running environments as a way of informing user interfaces of all
kinds, including "standard" interactive, text-based REPLs).

The network protocol used is simple, depending neither
on JVM or Clojure specifics, thereby allowing (encouraging?) the development
of non-Clojure REPL clients.  The REPLs operational semantics are such
that essentially any future non-JVM Clojure implementations should be able to
implement it, with allowances for hosts that lack the concurrency primitives to
support e.g. asynchronous evaluation, interrupts, etc.

For more information about the motivation, architecture, use cases, and
discussion related to nREPL, see the see the original design notes, available
[here](https://docs.google.com/document/edit?id=1dnb1ONTpK9ttO5W4thxiXkU5Ki89gK62anRqKEK4YZI&authkey=CMuszuMI&hl=en#),
and the [notes](https://github.com/clojure/tools.nrepl/wiki/nREPL.Next) and
[discussion](groups.google.com/group/clojure-dev/browse_frm/thread/6e366c1d0eaeec59)
around its recent redesign.

### Design

nREPL largely consists of three abstractions: handlers, middleware, and
transports.  These are largely analogous to the handlers, middleware, and
adapters of [Ring](https://github.com/mmcgrana/ring), though there are some
important semantic differences, especially around transports.

#### Handlers

nREPL is fundamentally message-oriented, where a message is a map of key/value
pairs.  _Handlers_ are functions accept a single incoming message as an argument,
and should return a truthy value indicating whether or not the provided message
was processed.  An nREPL server is started with a single handler function, which
will be used to process messages for the lifetime of the server.

(This is because the most prevalent operation — evaluation
of Clojure code — is fundamentally asynchronous.)

Messages are guaranteed to contain the following slots:

* `:transport` The [transport](#transports) that should be used to send all
responses precipitated by a given message.
* `:op` The operation to perform; roughly, the type of message

Depending on its `:op`, a message might be required to contain other slots, and
might optionally contain others.  Each request should contain a unique
`:id`.

Responses are also messages, maps of key/value pairs, the content of which
depend entirely upon the type of message for which the responses are produced.
Every request must provoke at least one and potentially many response messages,
each of which should contain an `:id` slot echoing that of the provoking
request.  Once a handler has completely processed a message, a response
containing a `:status` of `:done` must be sent.  Some operations necessitate
that additional responses related to the processing of a request are sent after
a `:done` `:status` is reported (e.g. delivering content written to `*out*` by
evaluated code that started a `future`).

Other statuses are possible, depending upon the semantics of the `:op` being
handled; in particular, if the message is malformed or incomplete for a
particular `:op`, then a response with an `:error` `:status` should be sent,
potentially with additional information about the nature of the problem. 

It is possible for an nREPL server to send messages to a client that
are not a direct response to a request (e.g. streaming content written to
`System/out` might be started/stopped by requests, but messages containing such
content can't be considered responses to those requests).

If the handler being used by an nREPL server returns a logically false value
(indicating that a message's `:op` was unrecognized), then the the server will
respond with a message containing a `:status` of `"unknown-op"`.

Generally, the handler that is provided as the `:handler` to
`clojure.tools.nrepl.server/start-server` is built up as a result of composing
multiple pieces of middleware.

#### Middleware

_Middleware_ are higher-order functions that compose additional functionality
onto or around a handler.  For example, some middleware that handles a `"time?"`
`:op` by replying with the local time on the server:

```clojure
(require '[clojure.tools.nrepl.transport :as t])
(use '[clojure.tools.nrepl.misc :only (response-for)])

(defn current-time
  [h]
  (fn [{:keys [op transport] :as msg}]
    (if (= "time?" op)
      (t/send transport (response-for msg :status :done :time (System/currentTimeMillis)))
      (h msg))))
```

A little silly, perhaps, but you should get the idea.  Nearly all of the same
patterns and expectations associated with Ring middleware should be applicable
to nREPL middleware.

It is recommended that `(constantly false)` always be the base handler; this
ensures that unhandled messages will always yield a logically false return
value.  For example, nREPL's default handler is constructed like so in the
`clojure.tools.nrepl.server` namespace:

```clojure
(defn default-handler
  "A default handler supporting interruptible evaluation, stdin, sessions, and
   readable representations of evaluated expressions via `pr`."
  []
  (-> (constantly false)
    clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
    clojure.tools.nrepl.middleware.pr-values/pr-values
    clojure.tools.nrepl.middleware.session/add-stdin
    clojure.tools.nrepl.middleware.session/session))
```

This combination — when paired with a suitably-capable client — aims to match
and exceed the functionality offered by the standard Clojure REPL.  Please see
the documentation for each of those middleware functions for details as to what
they expect in requests, and what they emit for responses.

#### Transports <a name="transports"/>

_Transports_ are roughly analogous to Ring's adapters: they provide an
implementation of a common protocol (`clojure.tools.nrepl.transport.Transport`)
to enable nREPL to send and receive messages without regard for the underlying
mode of communication.  Some transport implementations may be usable by a client
as well as the server, but this is not expected to be common.

nREPL's default transport utilizes
[bencode](http://wiki.theory.org/BitTorrentSpecification#Bencoding)-encoded
messages sent over sockets; it is used by default by
`clojure.tools.nrepl.server/start-server` and `clojure.tools.nrepl/connect`.

On the other hand, a `Transport` implementation suitable for exposing nREPL over
plain-text sockets (`clojure.tools.nrepl.transport/tty`) is only usable by the
server.  Its only purpose is to enable the use of simpler/less capable tools
(e.g. `telnet` et al.) to connect to an nREPL backend, so there's little reason
to support client usage in such a scenario.  Simiarly, an HTTP `Transport`
(taking the form of a Ring handler) would expect clients to connect via e.g.
`curl`, a browser, or a Javascript-powered HTTP console.

<!--
#### Client Requests

Only one type of request message is defined, which is used for sending code
to the server to be loaded/evaluated.  All other REPL behaviours (such as environment
introspection, symbol completion, quit/restart, etc.) can be accomplished through
evaluating code.

Each request message consists of the following slots:

- `id` *Must* be a unique string identifying the request. UUIDs are suitable, and automatic
in the provided nREPL client.
- `code` The code to be evaluated.
- `in` A string containing content to be bound (via a Reader) to `*in*` for the
  duration of `code`'s execution
- `timeout` The maximum amount of time, in milliseconds, that the provided code
  will be allowed to run before a `timeout` response is sent.  This is optional;
if not provided, a default timeout will be assigned by the server (currently
always 60s).

Only `id` and `code` are required in every request.

#### Server Responses

The server will produce multiple messages in response to each client request, each of
which can have the following slots:

- `id` The ID of the request for which the response was generated.
- `ns` The stringified value of `*ns*` at the time of the response message's generation.
- `out` Contains content written to `*out*` while the request's code was being evaluated.
Messages containing `*out*` content may be sent at the discretion of the server, though
at minimum corresponding with flushes of the underlying stream/writer.
- `err` Same as `out`, but for `*err*`.
- `value` The result of printing a result of evaluating a form in the code sent
  in the corresponding request.  More than one value may be sent, if more than
one form can be read from the request's code string.  In contrast to the output
written to `*out*` and `*err*`, this may be usefully/reliably read and utilized
by the client, e.g. in tooling contexts, assuming the evaluated code returns a
printable and readable value.  Interactive clients will likely want to simply
stream `value`'s content to their UI's primary output / log.  Values are printed
with `prn` by default; alternatively, if all of the following conditions hold at
the time of printing, a pretty-printer will be used instead:
    1. One of the following is available:
        1. Clojure [1.2.0) (and therefore `clojure.pprint`)
        2. Clojure Contrib (and therefore `clojure.contrib.pprint`)
    2. `clojure.tools.nrepl/*pretty-print*` is `set!`'ed to true (which persists
       for the duration of the client connection)
- `status` One of:
    - `error` Indicates an error occurred evaluating the requested code.  The
      related exception is bound to `*e` per usual, and printed to `*err*`,
which will be delivered via a later message.  The caught exception is printed
using `prn` by default; if `clojure.tools.nrepl/*print-stack-trace-on-error*` is
`set!`'ed to true (which persists for the duration of the client connection),
then exception stack traces are automatically printed to `*err*` instead. 
    - `timeout` Indicates that the timeout specified by the requesting message
      expired before the code was fully evaluated.
    - `interrupted` Indicates that the evaluation of the request's code was
      interrupted.
    - `server-failure` An unrecoverable error occurred in conjunction with the
      processing of the request's code.  This probably indicates a bug or fatal
system fault in the server itself. 
    - `done` Indicates that the request associated with the specified ID has
      been completely processed, and no further messages related to it will be
sent.  This does not imply "success", only that a timeout or interrupt condition
was not encountered.

Only the `id` and `ns` slots will always be defined. Other slots are only defined when new
related data is available (e.g. `err` when new content has been written to `*err*`, etc).

Note that evaluations that timeout or are interrupted may nevertheless result in multiple
response messages being sent prior to the timeout or interrupt occurring.

### Timeouts and Interrupts

Each message has a timeout associated with it, which controls the maximum time that a
message's code will be allowed to run before being interrupted and a response message
being sent indicating a status of `timeout`.

The processing of a message may be interrupted by a client by sending another message
containing code that invokes the `clojure.tools.nrepl/interrupt` function, providing it with
the string ID of the message to be interrupted.  The interrupt will be responded to
separately as with any other message. (The provided client implementation provides a
simple abstraction for handling responses that makes issuing interrupts very
straightforward.)

*Note that interrupts are performed on a “best-effort” basis, and are subject to the
limitations of Java’s threading model.  For more read
[here](http://download.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#interrupt%28%29)
and
[here](http://download.oracle.com/javase/1.5.0/docs/guide/misc/threadPrimitiveDeprecation.html).*
-->

## Change Log

`0.2.0`:

Top-to-bottom redesign

`0.0.6`:

Never released; initial prototype of "rich content" support that (in part)
helped motivate a re-examination of the underlying protocol and design.

`0.0.5`:

- added Clojure 1.3.0 (ALPHA) compatibility

`0.0.4`:

- fixed (hacked) obtaining `clojure.test` output when `clojure.test` is initially
loaded within an nREPL session
- eliminated 1-minute default timeout on expression evaluation
- all standard REPL var bindings are now properly established and maintained within a session

## Thanks

Thanks to the following Clojure masters for their helpful feedback during the initial
design phases of nREPL:

* Justin Balthrop
* Meikel Brandmeyer
* Hugo Duncan
* Christophe Grand
* Anthony Grimes
* Phil Hagelberg
* Rich Hickey
* Chris Houser
* Colin Jones
* Laurent Petit
* Eric Thorsen

## License

Copyright © 2010 - 2012 Chas Emerick and contributors.

Licensed under the EPL. (See the file epl.html.)
