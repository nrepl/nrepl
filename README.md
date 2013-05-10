# nREPL

[nREPL](http://github.com/clojure/tools.nrepl) is a Clojure *n*etwork REPL that
provides a REPL server and client, along with some common APIs
of use to IDEs and other tools that may need to evaluate Clojure
code in remote environments.

## Usage

### "Installation" <a name="installing"/>

nREPL is available in Maven central. Add this to your Leiningen
`project.clj` `:dependencies`:

```clojure
[org.clojure/tools.nrepl "0.2.3"]
```

Or, add this to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>org.clojure</groupId>
  <artifactId>tools.nrepl</artifactId>
  <version>0.2.3</version>
</dependency>
```

A list of all prior releases are available
[here](http://search.maven.org/#search|gav|1|g%3A%22org.clojure%22%20AND%20a%3A%22tools.nrepl%22).

Please note the changelog below.

nREPL is compatible with Clojure 1.2.0 - 1.5.0.

Please post general questions or discussion on either the
[clojure-dev](http://groups.google.com/group/clojure-dev/) or
[clojure-tools](http://groups.google.com/group/clojure-tools) mailing lists.
Bug reports and such may be filed into [nREPL's
JIRA](http://dev.clojure.org/jira/browse/NREPL).

nREPL's generated API documentation is available
[here](http://clojure.github.com/tools.nrepl/).  A [history of nREPL
builds](http://build.clojure.org/job/tools.nrepl/) is available, as well as [a
compatibility test
matrix](http://build.clojure.org/job/tools.nrepl-test-matrix/), verifying
nREPL's functionality against multiple versions of Clojure and multiple JVMs.

### Connecting to an nREPL server

Most of the time, you will connect to an nREPL server using an existing
client/tool.  Tools that support nREPL include:

* [Leiningen](https://github.com/technomancy/leiningen) (starting with v2)
* [Counterclockwise](http://code.google.com/p/counterclockwise/) (Clojure
  plugin for Eclipse)
* [nrepl.el](https://github.com/kingtim/nrepl.el) (nREPL mode for Emacs)
* [foreplay.vim](https://github.com/tpope/vim-foreplay) (Clojure + nREPL
  support for vim)
* [Reply](https://github.com/trptcolin/reply/)

If your preferred Clojure development environment supports nREPL, you're done.
Use it or connect to an existing nREPL endpoint, and you're done.

#### Talking to an nREPL endpoint programmatically

If you want to connect to an nREPL server using the default transport, something
like this will work:

```clojure
=> (require '[clojure.tools.nrepl :as repl])
nil
=> (with-open [conn (repl/connect :port 59258)]
     (-> (repl/client conn 1000)    ; message receive timeout required
       (repl/message {:op "eval" :code "(+ 2 3)"})
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

Each message must contain at least an `:op` (or `"op"`) slot, which specifies
the "type" of the operation to be performed.  The operations supported by an
nREPL endpoint are determined by the handlers and middleware stack used when
starting that endpoint; the default middleware stack (described below) supports
a particular set of operations, [detailed
here](https://github.com/clojure/tools.nrepl/blob/master/doc/ops.md). 

### Embedding nREPL, starting a server

If your project uses Leiningen (v2 or higher), you already have access to an
nREPL server for your project via `lein repl` (or, `lein repl :headless` if you
don't need the Reply terminal-based nREPL client to connect to the resulting
nREPL server).

Otherwise, it can be extremely useful to have your application host a REPL
server whereever it might be deployed; this can greatly simplify debugging,
sanity-checking, panicked code patching, and so on.

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

Note that nREPL is not limited to its default messaging protocol, nor to its
default use of sockets.  nREPL provides a _transport_ abstraction for
implementing support for alternative protocols and connection methods.
Alternative transport implementations are available, and implementing your own
is not difficult; read more about transports [here](#transports).

### Building nREPL

Releases are available from Maven Central, and SNAPSHOT builds from master's
HEAD are automatically deployed to Sonatype's OSS repository (see
[this](http://dev.clojure.org/display/doc/Maven+Settings+and+Repositories) for
how to configure Leiningen or Maven to use OSS-snapshots), so building nREPL
shouldn't ever be necessary.  But, if you insist:

0. Clone the repo
1. Make sure you have maven installed
2. Run the maven build, either:
    1. `mvn package`: This will produce an nREPL jar file in the `target`
directory, and run all tests against Clojure 1.2.0.
    2. `mvn verify`: This does the same, but also runs the tests with
other Clojure "profiles" (one for each supported version of Clojure). 

## Why nREPL?

nREPL has been designed with the aim of ensuring that it satisfies the
requirements of both application developers (in support of activities ranging
from interactive remote debugging and experimentation in development
contexts through to more advanced use cases such as updating deployed
applications) as well as toolmakers (providing a standard way to connect to and
introspect running environments as a way of informing user interfaces of all
kinds, including "standard" interactive, text-based REPLs).

The default network protocol used is simple, depending neither
on JVM or Clojure specifics, thereby allowing (encouraging?) the development
of non-Clojure REPL clients.  The REPLs operational semantics are such
that essentially any non-JVM Clojure implementation should be able to
implement it, with allowances for hosts that lack the concurrency primitives to
support e.g. asynchronous evaluation, interrupts, etc.

For more information about the motivation, architecture, use cases, and
discussion related to nREPL, see the see the original design notes,
availabl[here](https://docs.google.com/document/edit?id=1dnb1ONTpK9ttO5W4thxiXkU5Ki89gK62anRqKEK4YZI&authkey=CMuszuMI&hl=en#),
and the [notes](https://github.com/clojure/tools.nrepl/wiki/nREPL.Next) and
[discussion](http://groups.google.com/group/clojure-dev/browse_frm/thread/6e366c1d0eaeec59)
around its recent redesign.

### Design

nREPL largely consists of three abstractions: handlers, middleware, and
transports.  These are roughly analogous to the handlers, middleware, and
adapters of [Ring](https://github.com/ring-clojure/ring), though there are some
important semantic differences. Finally, nREPL is fundamentally message-oriented
and asynchronous (in contrast to most REPLs that build on top of streams
provided by e.g.  terminals).

#### Messages

nREPL messages are maps.  The keys and values that may be included in messages
depends upon the transport being used; different transports may encode messages
differently, and therefore may or may not be able to represent certain data
types.

Each message sent to an nREPL endpoint constitutes a "request" to perform a
particular operation, which is indicated by a `"op"` entry.  Each operation may
further require the incoming message to contain other data.  Which data an
operation requires or may accept varies; for example, a message to evaluate
some code might look like this:

```clojure
{"op" "eval" "code" "(+ 1 2 3)"}
```

The result(s) of performing each operation may be sent back to the nREPL client
in one or more response messages, the contents of which again depend upon the
operation.

#### Transports <a name="transports"/>

<!-- talk about strings vs. bytestrings, the encoding thereof, etc when we
figure that out -->

_Transports_ are roughly analogous to Ring's adapters: they provide an
implementation of a common protocol (`clojure.tools.nrepl.transport.Transport`)
to enable nREPL clients and servers to send and receive messages without regard
for the underlying channel or particulars of message encoding.

nREPL includes two transports, both of which are socket-based: a "tty"
transport that allows one to connect to an nREPL endpoint using e.g. `telnet`
(which therefore supports only the most simplistic interactive evaluation of
expressions), and one that uses
[bencode](http://wiki.theory.org/BitTorrentSpecification#Bencoding) to encode
nREPL messages over sockets.  It is the latter that is used by default by
`clojure.tools.nrepl.server/start-server` and `clojure.tools.nrepl/connect`.

[Other nREPL transports are provided by the community]
(https://github.com/clojure/tools.nrepl/wiki/Extensions).

#### Handlers

_Handlers_ are functions that accept a single incoming message as an argument.
An nREPL server is started with a single handler function, which will be used
to process messages for the lifetime of the server.  Note that handler return
values are _ignored_; results of performing operations should be sent back to
the client via the transport in use (which will be explained shortly).  This
may seem peculiar, but is motivated by two factors:

* Many operations — including something as simple as code evaluation — is
  fundamentally asynchronous with respect to the nREPL server
* Many operations can produce multiple results (e.g. evaluating a snippet of
  code like `"(+ 1 2) (def a 6)`).

Thus, messages provided to nREPL handlers are guaranteed to contain a
`:transport` entry containing the [transport](#transports) that should be used
to send all responses precipitated by a given message.  (This slot is added by
the nREPL server itself, thus, if a client sends any message containing a
`"transport"` entry, it will be bashed out by the `Transport` that was the
source of the message.)  Further, all messages provided to nREPL handlers have
keyword keys (as per `clojure.walk/keywordize-keys`).

Depending on its `:op`, a message might be required to contain other slots, and
might optionally contain others.  It is generally the case that request
messages should contain a globally-unique `:id`.
Every request must provoke at least one and potentially many response messages,
each of which should contain an `:id` slot echoing that of the provoking
request.

Once a handler has completely processed a message, a response
containing a `:status` of `:done` must be sent.  Some operations necessitate
that additional responses related to the processing of a request are sent after
a `:done` `:status` is reported (e.g. delivering content written to `*out*` by
evaluated code that started a `future`).
Other statuses are possible, depending upon the semantics of the `:op` being
handled; in particular, if the message is malformed or incomplete for a
particular `:op`, then a response with an `:error` `:status` should be sent,
potentially with additional information about the nature of the problem. 

It is possible for an nREPL server to send messages to a client that are not a
direct response to a request (e.g. streaming content written to `System/out`
might be started/stopped by requests, but messages containing such content
can't be considered responses to those requests).

If the handler being used by an nREPL server does not recognize or cannot
perform the operation indicated by a request message's `:op`, then it should
respond with a message containing a `:status` of `"unknown-op"`.

It is currently the case that the handler provided as the `:handler` to
`clojure.tools.nrepl.server/start-server` is generally built up as a result of
composing multiple pieces of middleware.

#### Middleware

_Middleware_ are higher-order functions that accept a handler and return a new
handler that may compose additional functionality onto or around the original.
For example, some middleware that handles a hypothetical `"time?"` `:op` by
replying with the local time on the server:

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

A little silly, but this pattern should be familiar to you if you have
implemented Ring middleware before.  Nearly all of the same patterns and
expectations associated with Ring middleware should be applicable to nREPL
middleware.

All of nREPL's provided default functionality is implemented in terms of
middleware, even foundational bits like session and eval support.  This default
middleware "stack" aims to match and exceed the functionality offered by the
standard Clojure REPL, and is available at
`clojure.tools.nrepl.server/default-middlewares`.  Concretely, it consists of a
number of middleware functions' vars that are implicitly merged with any
user-specified middleware provided to
`clojure.tools.nrepl.server/default-handler`.  To understand how that implicit
merge works, we'll first need to talk about middleware "descriptors".

[Other nREPL middlewares are provided by the community]
(https://github.com/clojure/tools.nrepl/wiki/Extensions).

(See [this documentation
listing](https://github.com/clojure/tools.nrepl/blob/master/doc/ops.md) for
details as to the operations implemented by nREPL's default middleware stack,
what each operation expects in request messages, and what they emit for
responses.)

##### Middleware descriptors and nREPL server configuration

It is generally the case that most users of nREPL will expect some minimal REPL
functionality to always be available: evaluation (and the ability to interrupt
evaluations), sessions, file loading, and so on.  However, as with all
middleware, the order in which nREPL middleware is applied to a base handler is
significant; e.g., the session middleware's handler must look up a user's
session and add it to the message map before delegating to the handler it wraps
(so that e.g. evaluation middleware can use that session data to stand up the
user's dynamic evaluation context).  If middleware were "just" functions, then
any customization of an nREPL middleware stack would need to explicitly repeat
all of the defaults, except for the edge cases where middleware is to be
appended or prepended to the default stack.

To eliminate this tedium, the vars holding nREPL middleware functions may have
a descriptor applied to them to specify certain constraints in how that
middleware is applied.  For example, the descriptor for the
`clojure.tools.nrepl.middleware.session/add-stdin` middleware is set thusly:

```clojure
(set-descriptor! #'add-stdin
  {:requires #{#'session}
   :expects #{"eval"}
   :handles {"stdin"
             {:doc "Add content from the value of \"stdin\" to *in* in the current session."
              :requires {"stdin" "Content to add to *in*."}
              :optional {}
              :returns {"status" "A status of \"need-input\" will be sent if a session's *in* requires content in order to satisfy an attempted read operation."}}}})
```

Middleware descriptors are implemented as a map in var metadata under a
`:clojure.tools.nrepl.middleware/descriptor` key.  Each descriptor can contain
any of three entries:

* `:requires`, a set containing strings or vars identifying other middleware
  that must be applied at a higher level than the middleware being described.
Var references indicate an implementation detail dependency; string values
indicate a dependency on _any_ middleware that handles the specified `:op`.
* `:expects`, the same as `:requires`, except the referenced middleware must
  exist in the final stack at a lower level than the middleware being
described.
* `:handles`, a map that documents the operations implemented by the
  middleware.  Each entry in this map must have as its key the string value of
the handled `:op` and a value that contains any of four entries:
  * `:doc`, a human-readable docstring for the middleware
  * `:requires`, a map of slots that the handled operation must find in request
    messages with the indicated `:op`
  * `:optional`, a map of slots that the handled operation may utilize from the
    request messages with the indicated `:op`
  * `:returns`, a map of slots that may be found in messages sent in response
    to handling the indicated `:op`

The values in the `:handles` map is used to support the `"describe"` operation,
which provides "a machine- and human-readable directory and documentation for
the operations supported by an nREPL endpoint" (see
`clojure.tools.nrepl.middleware/describe-markdown`, and the results of
`"describe"` and `describe-markdown`
[here](https://github.com/clojure/tools.nrepl/blob/master/doc/ops.md)).

The `:requires` and `:expects` entries control the order in which
middleware is applied to a base handler.  In the `add-stdin` example above,
that middleware will be applied after any middleware that handles the `"eval"`
operation, but before the `clojure.tools.nrepl.middleware.session/session`
middleware.  In the case of `add-stdin`, this ensures that incoming messages
hit the session middleware (thus ensuring that the user's dynamic scope —
including `*in*` — has been added to the message) before the `add-stdin`'s
handler sees them, so that it may append the provided `stdin` content to the
buffer underlying `*in*`.  Additionally, `add-stdin` must be "above" any `eval`
middleware, as it takes responsibility for calling `clojure.main/skip-if-eol`
on `*in*` prior to each evaluation (in order to ensure functional parity with
Clojure's default stream-based REPL implementation).

The specific contents of a middleware's descriptor depends entirely on its
objectives: which operations it is to implement/define, how it is to modify
incoming request messages, and which higher- and lower-level middlewares are to
aid in accomplishing its aims.

nREPL uses the dependency information in descriptors in order to produce a
linearization of a set of middleware; this linearization is exposed by
`clojure.tools.nrepl.middleware/linearize-middleware-stack`, which is
implicitly used by `clojure.tools.nrepl.server/default-handler` to combine the
default stack of middleware with any additional provided middleware vars.  The
primary contribution of `default-handler` is to use
`clojure.tools.nrepl.server/unknown-op` as the base handler; this ensures that
unhandled messages will always produce a response message with an `:unknown-op`
`:status`.  Any handlers otherwise created (e.g. via direct usage of
`linearize-middleware-stack` to obtain a ordered sequence of middleware vars)
should do the same, or use a similar alternative base handler.

<!--

#### Server Responses

The server will produce multiple messages in response to each client request,
each of which can have the following slots:

- `id` The ID of the request for which the response was generated.
- `ns` The stringified value of `*ns*` at the time of the response message's
  generation.
- `out` Contains content written to `*out*` while the request's code was being
  evaluated.  Messages containing `*out*` content may be sent at the discretion
of the server, though at minimum corresponding with flushes of the underlying
stream/writer.
- `err` Same as `out`, but for `*err*`.
- `value` The result of printing a result of evaluating a form in the code sent
  in the corresponding request.  More than one value may be sent, if more than
one form can be read from the request's code string.  In contrast to the output
written to `*out*` and `*err*`, this may be usefully/reliably read and utilized
by the client, e.g. in tooling contexts, assuming the evaluated code returns a
printable and readable value.  Interactive clients will likely want to simply
stream `value`'s content to their UI's primary output / log.  Values are
printed with `prn` by default; alternatively, if all of the following
conditions hold at the time of printing, a pretty-printer will be used instead:
    1. One of the following is available:
        1. Clojure [1.2.0) (and therefore `clojure.pprint`)
        2. Clojure Contrib (and therefore `clojure.contrib.pprint`)
    2. `clojure.tools.nrepl/*pretty-print*` is `set!`'ed to true (which
       persists for the duration of the client connection)
- `status` One of:
    - `error` Indicates an error occurred evaluating the requested code.  The
      related exception is bound to `*e` per usual, and printed to `*err*`,
which will be delivered via a later message.  The caught exception is printed
using `prn` by default; if `clojure.tools.nrepl/*print-stack-trace-on-error*`
is `set!`'ed to true (which persists for the duration of the client
connection), then exception stack traces are automatically printed to `*err*`
instead. 
    - `timeout` Indicates that the timeout specified by the requesting message
      expired before the code was fully evaluated.
    - `interrupted` Indicates that the evaluation of the request's code was
      interrupted.
    - `server-failure` An unrecoverable error occurred in conjunction with the
      processing of the request's code.  This probably indicates a bug or fatal
system fault in the server itself. 
    - `done` Indicates that the request associated with the specified ID has
      been completely processed, and no further messages related to it will be
sent.  This does not imply "success", only that a timeout or interrupt
condition was not encountered.

Only the `id` and `ns` slots will always be defined. Other slots are only
defined when new related data is available (e.g. `err` when new content has
been written to `*err*`, etc).

Note that evaluations that timeout or are interrupted may nevertheless result
in multiple response messages being sent prior to the timeout or interrupt
occurring.

### Timeouts and Interrupts

Each message has a timeout associated with it, which controls the maximum time
that a message's code will be allowed to run before being interrupted and a
response message being sent indicating a status of `timeout`.

The processing of a message may be interrupted by a client by sending another
message containing code that invokes the `clojure.tools.nrepl/interrupt`
function, providing it with the string ID of the message to be interrupted.
The interrupt will be responded to separately as with any other message. (The
provided client implementation provides a simple abstraction for handling
responses that makes issuing interrupts very straightforward.)

*Note that interrupts are performed on a “best-effort” basis, and are subject
to the limitations of Java’s threading model.  For more read
[here](http://download.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#interrupt%28%29)
and
[here](http://download.oracle.com/javase/1.5.0/docs/guide/misc/threadPrimitiveDeprecation.html).*
-->

## Change Log

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

## Thanks

Thanks to the following Clojure masters for their helpful feedback during the
initial design phases of nREPL:

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

Copyright © 2010 - 2013 Chas Emerick and contributors.

Licensed under the EPL. (See the file epl.html.)
