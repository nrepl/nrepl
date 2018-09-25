## What's the difference between contrib's nREPL and this one?

See [our history](https://nrepl.readthedocs.io/en/latest/about/history/).
Very simply put - this project is the continuation of the contrib project.

## Does nREPL support ClojureScript?

Yes, it does, although you'll need additional middleware to enable the
ClojureScript support
(e.g. [piggieback](https://github.com/nrepl/piggieback) or
[shadow-cljs](https://github.com/thheller/shadow-cljs)).

## Does nREPL support ClojureCLR?

nREPL currently doesn't support ClojureCLR. The reason for this is
that it leverages Java APIs internally. There's an [nREPL port for
ClojureCLR](https://github.com/clojure/clr.tools.nrepl), but it's not
actively maintained and it doesn't behave like the Clojure nREPL.

## When is nREPL 1.0 going to be released?

There's no exact roadmap for the 1.0 release. Roughly speaking the idea is to
release 1.0 once everything essential has been migrated from the legacy contrib nREPL
to the new nREPL (e.g. lein, boot, key middleware) and we've cleaned up the most
important tickets from our backlog.

## Are there any interesting nREPL extensions worth checking out?

Sure! See [third party middleware](third_party_middleware.md) for details.

## Where can I get help regarding nREPL?

See the [Support](about/support.md) section of the manual.

## What should I do if I run into some issues with nREPL?

Don't panic! Next step - visit the [Troubleshooting](troubleshooting.md) section of
the manual.

## How can I help the project?

There are many ways in which you can help nREPL:

* Donate funds
* Work on improving the documentation
* Solve open issues
* File bug reports and suggestions for improvements
* Promote nREPL via blog posts or at meetups and conferences
* Invite members of the nREPL team to speak about nREPL at meetups and conferences
