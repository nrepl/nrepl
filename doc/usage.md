## Usage

### Starting a server

There are many ways to start an nREPL server. Most often you'd start
it using some build tool, but you can also embed the server in your
application and start it from it. Here we'll outline the most
popular options available for you today.

#### Using Leiningen

[Leiningen][] has built-in support for nREPL since
version 2. Just do:

```
lein repl
```

And you're all set. By default Lein will also connect to the running nREPL server using the
popular command-line nREPL client [REPL-y][]. If you don't need the terminal REPL you can
also start nREPL in headless mode:

```
lein repl :headless
```

#### Using Boot

[Boot][] is a popular alternative to Leiningen, that also has build-in support for nREPL:

```
boot repl
```

#### Using Clojure CLI tools

If you're into the `clj` command you can take advantage of nREPL's built-in command-line interface
(`nrepl.cmdline`).

Add this alias to `~/.clojure/deps.edn`:

``` clojure
{
;; ...
:aliases {:nREPL
          {:extra-deps
            {nrepl/nrepl {:mvn/version "0.4.4"}}}}
}
```

Then you can simply run the nREPL server in headless mode like this:

``` shell
clj -R:nREPL -m nrepl.cmdline"
```

A good practice is add whatever nREPL middleware you want to use to
the `nREPL` profile, so you can easily activate them when needed. Here's
how you can easily start a ClojureScript capable nREPL:

``` clojure
{
;; ...
:aliases {:nrepl
          {:extra-deps
            {nrepl/nrepl {:mvn/version "0.4.4"}
             cider/piggieback {:mvn/version "0.3.8"}}}}
}
```

``` shell
clj -R:nrepl -m nrepl.cmdline --middleware "[cider.piggieback/wrap-cljs-repl]"
```

Here's a listing of all the options available via nREPL's command-line
interface (this output was simply generated with `--help`):

```
Usage:

  --interactive            Start nREPL and connect to it with the built-in client.
  --color                  Use colors to differentiate values from output in the REPL. Must be combined with --interactive.
  --bind                   Bind address, by default "::" (falling back to "localhost" if "::" isn't resolved by the underlying network stack).
  --port PORT              Start nREPL on PORT. Defaults to 0 (random port) if not specified.
  --ack ACK-PORT           Acknowledge the port of this server to another nREPL server running on ACK-PORT.
  --handler HANDLER        The nREPL message handler to use for each incoming connection; defaults to the result of `(nrepl.server/default-handler)`.
  --middleware MIDDLEWARE  A sequence of vars, representing middleware you wish to mix in to the nREPL handler.
  --help                   Show this help message.
```

### Connecting to an nREPL server

Most of the time, you will connect to an nREPL server using an existing
client/tool.  Tools that support nREPL include:

* [CIDER](https://github.com/clojure-emacs/cider) (Clojure Interactive
  Development Environment that Rocks for Emacs)
* [Cursive](https://cursiveclojure.com) (Clojure IDE/plugin for IntelliJ Idea)
* [Counterclockwise](https://github.com/ccw-ide/ccw) (Clojure IDE/plugin
  for Eclipse)
* [monroe](https://github.com/sanel/monroe) (nREPL client for Emacs)
* [fireplace.vim](https://github.com/tpope/vim-fireplace) (Clojure + nREPL
  support for vim)
* [Proto REPL](https://github.com/jasongilman/proto-repl) (Proto REPL is a Clojure development environment and REPL for Atom)
* [Calva](https://github.com/BetterThanTomorrow/calva) (Clojure & ClojureScript support for VS Code)
* [REPL-y][] (command-line client for nREPL)

Both Leiningen and Boot use internally REPL-y, as their command-line nREPL client.

If your preferred Clojure development environment supports nREPL, you're done.
Use it or connect to an existing nREPL endpoint, and you're done.

#### Using the built-in client

nREPL ships with a very simple command-line client that you can use for some basic
interactions with the server. The following command will start an nREPL server
and connect with it using the built-in client.

```
clj -Sdeps '{:deps {nrepl {:mvn/version "0.4.4"}}}' -m nrepl.cmdline --interactive
nREPL server started on port 59403 on host 0:0:0:0:0:0:0:0 - nrepl://0:0:0:0:0:0:0:0:59403
nREPL 0.4.4
Clojure 1.9.0
Java HotSpot(TM) 64-Bit Server VM 10.0.1+10
user=> (+ 1 2)
3
```

Most users, however, are advised to use REPL-y or their favourite
editor instead for optimal results.

#### Talking to an nREPL endpoint programmatically

If you want to connect to an nREPL server using the default transport, something
like this will work:

```clojure
=> (require '[nrepl.core :as repl])
nil
=> (with-open [conn (repl/connect :port 59258)]
     (-> (repl/client conn 1000)    ; message receive timeout required
       (repl/message {:op "eval" :code "(+ 2 3)"})
       repl/response-values))
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
here](ops.md).

### Embedding nREPL, starting a server

If your project uses Leiningen (v2 or higher), you already have access to an
nREPL server for your project via `lein repl` (or, `lein repl :headless` if you
don't need the Reply terminal-based nREPL client to connect to the resulting
nREPL server).

Otherwise, it can be extremely useful to have your application host a REPL
server whereever it might be deployed; this can greatly simplify debugging,
sanity-checking, panicked code patching, and so on.

nREPL provides a socket-based server that you can trivially start from your
application.  [Add it to your project's dependencies](installation.md), and add code
like this to your app:

```clojure
=> (use '[nrepl.server :only (start-server stop-server)])
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
```

You can stop the server with `(stop-server server)`.

#### Server options

Note that nREPL is not limited to its default messaging protocol, nor to its
default use of sockets.  nREPL provides a _transport_ abstraction for
implementing support for alternative protocols and connection methods.
Alternative transport implementations are available, and implementing your own
is not difficult; read more about transports [here](design.md#transports).

### Hot-loading dependencies

From time to time you'd want to experiment with some library without
adding it as a dependency of your project.  You can easily achieve
this with `tools.deps` or `pomegranate`. Let's start with a `tools.deps` example:

```
clj -Sdeps '{:deps {nrepl {:mvn/version "0.4.4"} org.clojure/tools.deps.alpha
                {:git/url "https://github.com/clojure/tools.deps.alpha.git"
                 :sha "d492e97259c013ba401c5238842cd3445839d020"}}}' -m nrepl.cmdline --interactive
network-repl
Clojure 1.9.0
user=> (use 'clojure.tools.deps.alpha.repl)
nil
user=> (add-lib 'org.clojure/core.memoize {:mvn/version "0.7.1"})
true
user=> (require 'clojure.core.memoize)
nil
user=>

```

Alternatively with `pomegranate` you can do the following:

```
❯ clj -Sdeps '{:deps {nrepl {:mvn/version "0.4.4"} com.cemerick/pomegranate {:mvn/version "1.0.0"}}}' -m nrepl.cmdline --interactive
network-repl
Clojure 1.9.0
user=> (use '[cemerick.pomegranate :only (add-dependencies)])
nil
user=> (add-dependencies :coordinates '[[org.clojure/core.memoize "0.7.1"]]
                         :repositories (merge cemerick.pomegranate.aether/maven-central
                                             {"clojars" "https://clojars.org/repo"}))
{[org.clojure/core.memoize "0.7.1"] #{[org.clojure/core.cache "0.7.1"] [org.clojure/clojure "1.6.0"]}, [org.clojure/core.cache "0.7.1"] #{[org.clojure/data.priority-map "0.0.7"]}, [org.clojure/data.priority-map "0.0.7"] nil, [org.clojure/clojure "1.6.0"] nil}
user=> (require 'clojure.core.memoize)
nil
```

[REPL-y]: https://github.com/trptcolin/reply/
[Boot]: https://github.com/boot-clj/boot
[Leiningen]: https://github.com/technomancy/leiningen
