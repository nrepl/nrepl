# nREPL

[nREPL](http://github.com/clojure/tools.nrepl) is a Clojure *n*etwork REPL
that provides a REPL server and client, along with some common APIs
of use to IDEs and other tools that may need to evaluate Clojure
code in remote environments.

## Usage

### "Installation"

### Embedding nREPL

This library is in its infancy.  More info to come.  In the meantime,
check out the tests or clojure.tools.nrepl.main for usage examples.

### Debugging

### Building nREPL

0. Clone the repo
1. Make sure you have maven installed
2. Run the maven build; run either:
    1. `mvn package`: This will produce an nREPL jar file in the `target`
directory, and run all tests with the most recently-released build
of Clojure (currently 1.2.0). Or,
    2. `mvn verify`: This does the same, but also runs the tests with
other Clojure "profiles" (currently v1.1.0 and v1.1.0 + clojure-contrib). 

## Need Help?

Ping `cemerick` on freenode irc or twitter.

## Why another REPL implementation?

There are various Clojure REPL implementations: most notably
[swank-clojure](http://github.com/technomancy/swank-clojure), as well as
others associated with various tools and IDEs.  So, why write another?

First, while swank-clojure is widely used due to its association with
emacs, there is no Clojure swank client implementation that non-emacs
tools could use.  Further, swank's
Common Lisp/SLIME roots mean that its design and future development
are not ideal for serving the needs of users of Clojure remote REPLs.  

Second, other network REPL implementations are incomplete and/or
not suitable for key use cases.

nREPL has been designed in conjunction with the leads of various
Clojure development tools, with the aim of ensuring that it satisfies the
requirements of both application developers (in support of activities ranging
from interactive remote debugging and experimentation in development
contexts through to more advanced use cases such as updating deployed
applications) as well as toolmakers (providing a standard way to
introspect running environments as a way of informing user interfaces
of all kinds).

It is hoped that users of emacs/SLIME will also be able to use nREPL, either
by extending SLIME itself to work with its protocol, or by implementing 
a swank-compatible adapter for nREPL.

The network protocol used is simple, depending neither
on JVM or Clojure specifics, thereby allowing (encouraging?) the development
of non-Clojure REPL clients.  The REPLs operational semantics are such
that essentially any future non-JVM Clojure implementations should be able to
implement it (hopefully within this same project as a separate batch
of methods), with allowances for hosts that lack the concurrency primitives
to support e.g. asynchronous evaluation, interrupts, etc.

For more information about the motivation, architecture, use cases, and
discussion related to nREPL, see the original design notes, available
[here](https://docs.google.com/document/edit?id=1dnb1ONTpK9ttO5W4thxiXkU5Ki89gK62anRqKEK4YZI&authkey=CMuszuMI&hl=en#).

## Specification

*This section will likely only be of use/interest to those looking to build
non-Clojure nREPL client implementations.*

It is intended that nREPL's protocol and implementation semantics are such that:

- compatible servers may be implemented for any version/host combination that Clojure
is likely to be found on (with reasonable degradation otherwise)
- non-Clojure and non-JVM clients may be written without undue difficulty

### Protocol

nREPL's protocol is text- and message-oriented, with all communications encoded using
UTF-8.

Each message is a set of key/value pairs prefixed by the number of entries in the
resulting map, each atom of which is delineated by a linebreak. Keys may only be strings;
values may be strings or numbers.  A pseudocode formalism for the message "format" might be:

    <integer>
    <EOL>
    (<string: key>
     <EOL>
     (<string: value> | <number: value>)
     <EOL>)+

String keys and values must be quoted and escaped per Clojure standards
(which should align well with e.g. json, etc).

One can connect to a running nREPL server with a telnet client and interact with
it using its "wire" message protocol; this isn't intended as a proper usage example,
but may be a handy way for toolmakers and REPL client authors to get a quick feel
for the protocol and/or do dirty debugging.  Example:

    [catapult:~] chas% telnet localhost <port-number>
    Trying 127.0.0.1...
    Connected to localhost.
    Escape character is '^]'.
    2
    "id"
    "foo"
    "code"
    "(println 5)"
    3
    "ns"
    "user"
    "id"
    "foo"
    "value"
    "nil\n"
    3
    "ns"
    "user"
    "out"
    "5\n"
    "id"
    "foo"
    3
    "status"
    "done"
    "ns"
    "user"
    "id"
    "foo"

### Websockets / STOMP support

*Coming Soon*

Interested in helping?  Ping `cemerick` on freenode irc or twitter.

### Messages

#### Client Requests

Only one type of request message is defined, which is used for sending code
to the server to be loaded/evaluated.  All other REPL behaviours (such as environment
introspection, symbol completion, quit/restart, etc.) can be accomplished through
evaluating code.

Each request message consists of the following slots:

- `id` *Must* be a unique string identifying the request. UUIDs are suitable, and automatic
in the provided nREPL client.
- `code` The code to be evaluated.
- `in` A string containing content to be bound (via a Reader) to `*in*` for the duration
of `code`'s execution
- `timeout` The maximum amount of time, in milliseconds, that the provided code will be
allowed to run before a `timeout` response is sent.  This is optional; if not provided,
a default timeout will be assigned by the server (currently always 60s).

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
- `value` The result of printing a result of evaluating
a form in the code sent in the corresponding request.  More than one value may be
sent, if more than one form can be read from the request's code string.
In contrast to the output written to `*out*` and `*err*`, this may be usefully/reliably
read and utilized by the client, e.g. in tooling contexts, assuming the evaluated code
returns a printable and readable value.  Interactive clients will likely want to simply
stream `value`'s content to their UI's primary output / log.
Values are printed with `prn` by default; alternatively, if all of the following conditions hold at
the time of printing, a pretty-printer will be used instead:
    1. One of the following is available:
        1. Clojure [1.2.0) (and therefore `clojure.pprint`)
        2. Clojure Contrib (and therefore `clojure.contrib.pprint`)
    2. `clojure.tools.nrepl/*pretty-print*` is `set!`'ed to true (which persists for the
duration of the client connection)
- `status` One of:
    - `error` Indicates an error occurred evaluating the requested code.  The related
exception is bound to `*e` per usual, and printed to `*err*`, which will be delivered
via a later message.
The caught exception is printed using `prn` by default; if
`clojure.tools.nrepl/*print-stack-trace-on-error*` is `set!`'ed to true (which persists for the duration
of the client connection), then exception stack traces are automatically printed to
`*err*` instead. 
    - `timeout` Indicates that the timeout specified by the requesting message
expired before the code was fully evaluated.
    - `interrupted` Indicates that the evaluation of the request's code was interrupted.
    - `server-failure` An unrecoverable error occurred in conjunction with the processing of
the request's code.  This probably indicates a bug or fatal system fault in the server itself. 
    - `done` Indicates that the request associated with the specified ID has been
completely processed, and no further messages related to it will be sent.  This does not
imply "success", only that a timeout or interrupt condition was not encountered.

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
and [here](http://download.oracle.com/javase/1.5.0/docs/guide/misc/threadPrimitiveDeprecation.html).*

## Thanks

Thanks to the following Clojure masters for their helpful feedback during the initial
design phases of nREPL:

* Justin Balthrop,
* Meikel Brandmeyer,
* Hugo Duncan,
* Christophe Grand,
* Phil Hagelberg,
* Rich Hickey,
* Chris Houser,
* Laurent Petit, and
* Eric Thorsen

## License

Copyright © 2010 Chas Emerick

Licensed under the EPL. (See the file epl.html.)
