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
clj -Sdeps '{:deps {nrepl {:mvn/version "0.4.5-SNAPSHOT"}}}' -m nrepl.cmdline --interactive
```

## Running the tests

The easiest way to run the tests is with the following command:

```
lein test-all
```

This will automatically run the tests for every supported Clojure
profile (e.g. 1.7, 1.8, 1.9). You can run only the tests for a
specific version of Clojure like this:

```
lein with-profile 1.9 test
```
