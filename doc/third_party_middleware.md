## Middlewares

Here's a list of notable nREPL middleware you might encounter in the wild.

!!! Warning

    Make sure you're using the latest versions of those middlewares, as
    all of them added support for nREPL 0.4+ fairly recently.

### Clojure Editor Support

* [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl): A collection of nREPL middleware designed to enhance CIDER (and Clojure editors in general).
* [refactor-nrepl](https://github.com/clojure-emacs/refactor-nrepl): A collection of functionality designed to support editor refactoring features.
* [sayid](http://bpiel.github.io/sayid/): A powerful tracing/debugging tool. It is a great alternative of CIDER-nREPL's
basic tracing functionality.

###  ClojureScript support to nREPL session

* [Piggieback](https://github.com/nrepl/piggieback)

The following ClojureScript REPLs are leveraging piggieback internally to provide
nREPL support.

* [Weasel](https://github.com/tomjakubowski/weasel)
* [figwheel](https://github.com/bhauman/lein-figwheel)
* [figwheel-main](https://github.com/bhauman/figwheel-main)
* [shadow-cljs](https://github.com/thheller/shadow-cljs) (it actually
only stubs the piggieback API, leverages its own nREPL middleware
internally for ClojureScript evaluation)

### HTTP support

* [drawbridge](https://github.com/nrepl/drawbridge)

### Deprecated Middleware

This section lists middlewares that were somewhat prominent in the
past, but were replaced by alternatives down the road.

* [nrepl-middleware](https://github.com/pallet/ritz/tree/develop/nrepl-middleware),
  part of [ritz](https://github.com/pallet/ritz) that provides a
  variety of nREPL middleware supporting various enhanced REPL
  operations (including apropos, javadoc lookup, code completion, and
  an alternative eval implementation). **(superseded by `cider-nrepl`)**
* [Javert](https://github.com/technomancy/javert) provides a basic
  object inspector. **(superseded by `cider-nrepl`)**
* [nrepl-profile](https://github.com/thunknyc/nrepl-profile): profiling middleware,
which was eventually integrated into `cider-nrepl`.


!!! Note

    This list doesn't aim to be complete. You can find more 3rd-party middlewares listed
    [here](https://github.com/nrepl/nREPL/wiki/Extensions).
