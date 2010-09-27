# nREPL

[nREPL](http://github.com/cemerick/nREPL) is a Clojure *n*etwork REPL
that provides a REPL server and client, along with some common APIs
of use to IDEs and other tools that may need to evaluate Clojure
code in remote environments.

## Usage

### "Installation"

### Embedding nREPL

This library is in its infancy.  More info to come.  In the meantime,
check out the tests or cemerick.nrepl.main for usage examples.

### Debugging

## Need Help?

Ping `cemerick` on freenode irc or twitter.

## Specification

It is intended that nREPL's protocol and implementation semantics are such that:

- compatible servers may be implemented for any version/host combination that Clojure
is likely to be found on (with reasonable degradation otherwise)
- non-Clojure and non-JVM clients may be written without undue difficulty 

### Protocol

nREPL's protocol is text- and message-oriented, with all communications encoded using
UTF-8.

Each message is a set of key/value pairs prefixed by the number of entries in the
resulting map, each atom of which is delineated by a linebreak. A pseudocode formalism
for the message "format" might be:

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

    [catapult:~] chas% telnet localhost 25000
    Trying 127.0.0.1...
    Connected to localhost.
    Escape character is '^]'.
    2
    "id"
    "foo"
    "code"
    "(println 5)"
    5
    "id"
    "foo"
    "ns"
    "user"
    "out"
    "5\nnil\n"
    "value"
    "nil"
    "status"
    "ok"

### Websockets support

*coming soon*

### Messages

nREPL provides for only one type of (request) message, which is used for sending code
to the server to be loaded/evaluated.  All other REPL behaviours (such as environment
introspection, symbol completion, quit/restart, etc.) can be accomplished through
evaluating code.

Each request message consists of the following slots:

- *id* Must be a unique string identifying the request. UUIDs are suitable, and automatic
       in the provided nREPL client.
- *code* The code to be evaluated.
- *timeout* The maximum amount of time, in milliseconds, that the provided code will be
       allowed to run before a `timeout` response is sent.  This is optional; if not provided,
       a default timeout will be assigned by the server (currently always 60s).

Server responses have the following slots:

- *id* The ID of the request for which the response was generated.
- *ns* The stringified value of `*ns*` after the request's code was finished being evaluated.
- *out* The intermingled content written to `*out*` and `*err*` while the request's
       code was being evaluated.
- *value* The result of printing (via `pr`) the last result in the body of code evaluated.
       In contrast to the output written to `*out*` and `*err*`, this may be usefully/reliably
       read and utilized by the client, e.g. in tooling contexts, assuming the evaluated code
       returns a printable and readable value.
- *status* One of:
    - `ok`
    - `error` Indicates an error occurred evaluating the requested code.  The `value` slot
       will contain the printed exception.
    - `timeout` Indicates that the timeout specified by the requesting message
       expired before the code was fully evaluated.
    - `interrupted` Indicates that the evaluation of the request's code was interrupted.

When a response's `status` is `timeout` or `interrupted`, the `value`, `out`, and `ns`
response slots will be absent.

### Timeouts and Interrupts

Each message has a timeout associated with it, which controls the maximum time that a
message's code will be allowed to run before being interrupted and a response message
being sent indicating a status of `timeout`.

The processing of a message may be interrupted by a client by sending another message
containing code that invokes the `cemerick.nrepl/interrupt` function, providing it with
the string ID of the message to be interrupted.  The interrupt will be responded to
separately as with any other message.

Note that nREPL's client provides a simple abstraction for handling responses that makes
issuing interrupts very straightforward.

## Why another REPL implementation?

There are various Clojure REPL implementations, including
[swank-clojure](http://github.com/technomancy/swank-clojure)
and others associated with various tools and IDEs.  So, why
another?

First, while swank-clojure is widely used due to its association with
emacs, there is no Clojure swank client implementation.  Further, swank's
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

## Thanks

Thanks to Laurent Petit, Eric Thorsen, Justin Balthrop, Christophe Grand,
Hugo Duncan, Meikel Brandmeyer, and Phil Hagelberg for their helpful feedback during the initial
design phases of nREPL.

## License

Copyright © 2010 Chas Emerick

Licensed under the EPL. (See the file epl.html.)
