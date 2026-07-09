.PHONY: test eastwood cljfmt kondo install deploy clean lint copy-sources-to-jdk javac javac-test docs verify-docs
.DEFAULT_GOAL := install

# Set bash instead of sh for the @if [[ conditions,
# and use the usual safety flags:
SHELL = /bin/bash -Ee

CLOJURE_VERSION ?= 1.12

javac:
	clojure -T:build javac

javac-test:
	clojure -T:build javac :with-tests true

test: javac-test
	clojure -M:$(CLOJURE_VERSION):dev:test$(EXTRA_ALIASES)

eastwood: clean javac-test
	clojure -M:eastwood

cljfmt:
	clojure -M:cljfmt check

kondo:
	clojure -M:kondo

cloverage:
	clojure -M:cloverage

docs:
	clojure -X:docs :file '"doc/modules/ROOT/pages/ops.adoc"' :version '"1.7.0"'

# Regenerate ops.adoc and fail if it differs from the committed version.
# This catches cases where middleware descriptors changed but the generated
# docs weren't regenerated (or ops.adoc was edited by hand).
verify-docs: docs
	@if ! git diff --quiet -- doc/modules/ROOT/pages/ops.adoc; then \
		echo "[Error] ops.adoc is out of sync with the middleware descriptors."; \
		echo "Run 'make docs' and commit the result."; \
		git --no-pager diff -- doc/modules/ROOT/pages/ops.adoc; \
		exit 1; \
	fi

lint: kondo cljfmt eastwood

# Deployment is performed via CI by creating a git tag prefixed with "v".
# Please do not deploy locally as it skips various measures.
deploy: check-env
	@if ! echo "$(CIRCLE_TAG)" | grep -q "^v"; then \
		echo "[Error] CIRCLE_TAG $(CIRCLE_TAG) must start with 'v'."; \
		exit 1; \
	fi
	# Clean is performed inside deploy task, no need to clean in Make
	export PROJECT_VERSION=$$(echo "$(CIRCLE_TAG)" | sed 's/^v//'); \
	clojure -T:build deploy :version "\"$$PROJECT_VERSION\""

# Usage: PROJECT_VERSION=99.99 make install
install: check-install-env
	clojure -T:build install :version '"$(PROJECT_VERSION)"'

clean:
	clojure -T:build clean

check-env:
ifndef CLOJARS_USERNAME
	$(error CLOJARS_USERNAME is undefined)
endif
ifndef CLOJARS_PASSWORD
	$(error CLOJARS_PASSWORD is undefined)
endif
ifndef CIRCLE_TAG
	$(error CIRCLE_TAG is undefined. Please only perform deployments by publishing git tags. CI will do the rest.)
endif

check-install-env:
ifndef PROJECT_VERSION
	$(error Please set PROJECT_VERSION as an env var beforehand.)
endif

verify-cljdoc:
	clojure -T:build verify-cljdoc
