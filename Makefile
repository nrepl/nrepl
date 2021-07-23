.PHONY: test docs eastwood cljfmt cloverage release deploy clean

VERSION ?= 1.10

test:

# We use kaocha on Clojure 1.9+, but revert to lein's built in
# runner with Clojure 1.7 and 1.8.

ifeq ($(VERSION),$(filter $(VERSION),1.9 1.10 master))
	lein with-profile -user,+$(VERSION),+test run -m kaocha.runner
else
	lein with-profile -user,+$(VERSION),+test test
endif

eastwood:
	lein with-profile -user,+$(VERSION),+eastwood eastwood

cljfmt:
	lein with-profile -user,+$(VERSION),+cljfmt cljfmt check

kondo:
	lein with-profile +clj-kondo run -m clj-kondo.main --lint src

cloverage:
	lein with-profile -user,+$(VERSION),+cloverage cloverage --codecov

# Roughly match what runs in CI using the current JVM
check: test eastwood kondo cljfmt cloverage

verify_cljdoc:
	curl -fsSL https://raw.githubusercontent.com/cljdoc/cljdoc/master/script/verify-cljdoc-edn | bash -s doc/cljdoc.edn

# When releasing, the BUMP variable controls which field in the
# version string will be incremented in the *next* snapshot
# version. Typically this is either "major", "minor", or "patch".

BUMP ?= patch

release:
	lein with-profile -user,+$(VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy:
	lein with-profile -user,+$(VERSION) deploy clojars

clean:
	lein clean
