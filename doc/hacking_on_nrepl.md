## Building nREPL

Releases are available from Clojars, and SNAPSHOT builds from master's
HEAD are automatically deployed there as well, so manually building
nREPL shouldn't ever be necessary (unless you're hacking on it).  But,
if you insist:

0. Clone the repo
1. Make sure you have lein installed
2. Run the lein build:

```
lein install
```

Afterwards you can simply do something like:

```
clj -Sdeps '{:deps {nrepl {:mvn/version "0.4.3-SNAPSHOT"}}}' -m nrepl.cmdline --interactive
```
