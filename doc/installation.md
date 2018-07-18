## Installation

nREPL is available in Clojars. Add this to your Leiningen
`project.clj` `:dependencies`:

```clojure
[nrepl "0.4.2"]
```

Or, add this to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>nrepl</groupId>
  <artifactId>nrepl</artifactId>
  <version>0.4.2</version>
</dependency>
```

!!! Warning

    Prior to version 0.3, nREPL used to be hosted on Maven Central and had
    a different deployment artefact - `org.clojure/clojure.tools.nrepl`.

Please note the changelog in `CHANGELOG.md`.

!!! Note

    nREPL is compatible with Clojure 1.8.0+ and Java 8+.

## Upgrading from nREPL 0.2.x to 0.4.x

A few major changes happened since nREPL 0.2.x:

* The artefact id changed from `org.clojure/clojure.tools.nrepl` to `nrepl/nrepl`.
* The namespace prefix changed from `clojure.tools.nrepl` to `nrepl`.
* The namespace `clojure.tools.nrepl` was renamed to `nrepl.core`.
* nREPL now targets Java 8+ and Clojure 1.8+ (it used to target Java 6 and Clojure 1.2)

Those changes can affect you in two ways:

* If you're working on nREPL extensions you'll need to update the nREPL namespaces you're referring to.
* If you're using third-party nREPL middleware you'll have to make sure it was updated for nREPL 0.4+.

Apart from those renamings it's business as usual - nREPL's API and
protocol remain exactly the same as they were in 0.2.x, and backwards
compatibility remains as important as always.

Currently both `boot` and `lein` still ship with the legacy nREPL 0.2.x, so you'll need to start nREPL 0.4+
manually if you want to use it.
