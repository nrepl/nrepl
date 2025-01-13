<p align="center">
  <img src="https://raw.github.com/nrepl/nrepl/master/logo/logo-w1280.png" width="640" alt="nREPL Logo"/>
</p>

----------
[![CircleCI](https://img.shields.io/circleci/build/github/nrepl/nrepl/master.svg)](https://circleci.com/gh/nrepl/nrepl/tree/master)
[![Spell-check Status](https://github.com/nrepl/nrepl/actions/workflows/spell_checking.yml/badge.svg)](https://github.com/nrepl/nrepl/actions/workflows/spell_checking.yml)
[![Clojars Project](https://img.shields.io/clojars/v/nrepl.svg)](https://clojars.org/nrepl/nrepl)
[![cljdoc badge](https://cljdoc.org/badge/nrepl/nrepl)](https://cljdoc.org/d/nrepl/nrepl/CURRENT)
[![Downloads](https://img.shields.io/clojars/dt/nrepl/nrepl?color=cornflowerblue)](https://clojars.org/nrepl/nrepl)
[![Backers on Open Collective](https://opencollective.com/nrepl/backers/badge.svg)](#backers)
[![Sponsors on Open Collective](https://opencollective.com/nrepl/sponsors/badge.svg)](#sponsors)

nREPL is a Clojure *n*etwork REPL that
provides a REPL server and client, along with some common APIs
of use to IDEs and other tools that may need to evaluate Clojure
code in remote environments.

nREPL powers [many well-known development tools](https://nrepl.org/nrepl/usage/clients.html).

## Usage

* Start an nREPL server (e.g. with `lein repl`)
* Connect to the server using any nREPL client (e.g. CIDER, Calva, vim-fireplace)
* Start hacking!

See the [documentation](https://nrepl.org/nrepl/usage/server.html) for way more information on the subject.

## API Documentation

You can find nREPL's API documentation on [cljdoc](https://cljdoc.org/d/nrepl/nrepl/CURRENT).

## Status

Extremely stable. nREPL's protocol and API are rock-solid and battle
tested. nREPL's team pledges to evolve them only in
backwards-compatible ways.

All experimental features are marked explicitly as such in nREPL's
documentation, but even those are fairly stable by most standards.

## FAQ

### How is this different from the "contrib" [tools.nrepl](https://github.com/clojure/tools.nrepl/) project?

Check the brief history of nREPL, available
[here](https://nrepl.org/nrepl/about/history.html).

### How does nREPL compare to other REPL servers (e.g. `prepl`)?

Check out [this detailed comparison](https://nrepl.org/nrepl/alternatives.html).

### Does nREPL support ClojureScript?

Yes and no. The reference nREPL implementation is Clojure-specific, but it can be extended with ClojureScript support
via the Piggieback middleware. In the future there may be implementations of nREPL that target ClojureScript directly.

### Does nREPL support other programming languages besides Clojure?

The nREPL protocol is language-agnostic and implementations of nREPL servers exist for [several programming languages](https://nrepl.org/nrepl/beyond_clojure.html).
Implementing new nREPL servers is [pretty simple](https://nrepl.org/nrepl/building_servers.html).

## Testing and Hacking around

See [Hacking on nREPL](doc/modules/ROOT/pages/hacking_on_nrepl.adoc).

## Contributors

This project exists thanks to all the people who contribute.
<a href="https://github.com/nrepl/nrepl/graphs/contributors"><img src="https://opencollective.com/nrepl/contributors.svg?width=890&button=false" /></a>


## Backers

Thank you to all our backers! 🙏 [[Become a backer](https://opencollective.com/nrepl#backer)]

<a href="https://opencollective.com/nrepl#backers" target="_blank"><img src="https://opencollective.com/nrepl/backers.svg?width=890"></a>


## Sponsors

Support this project by becoming a sponsor. Your logo will show up here with a link to your website. [[Become a sponsor](https://opencollective.com/nrepl#sponsor)]

<a href="https://opencollective.com/nrepl/sponsor/0/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/0/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/1/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/1/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/2/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/2/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/3/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/3/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/4/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/4/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/5/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/5/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/6/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/6/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/7/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/7/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/8/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/8/avatar.svg"></a>
<a href="https://opencollective.com/nrepl/sponsor/9/website" target="_blank"><img src="https://opencollective.com/nrepl/sponsor/9/avatar.svg"></a>



## License

Copyright © 2010-2025 Chas Emerick, Bozhidar Batsov and contributors.

Licensed under the EPL. (See the file epl.html.)
