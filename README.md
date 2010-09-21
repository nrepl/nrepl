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

### Protocol

### Messages

### Timeouts and Interrupts

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
