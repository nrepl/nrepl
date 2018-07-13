## What's the difference between contrib's nREPL and this one?

See [our history](https://nrepl.readthedocs.io/en/latest/about/history/).
Very simply put - this project is the continuation of the contrib project.

## Does nREPL support ClojureCLR?

nREPL currently doesn't support ClojureCLR. The reason for this is
that it leverages Java APIs internally. There's an [nREPL port for
ClojureCLR](https://github.com/clojure/clr.tools.nrepl), but it's not
actively maintained and it doesn't behave like the Clojure nREPL.
