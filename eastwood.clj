(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'clojure.test/is}
  :within-depth 1
  :reason "The `is` macro commonly expands to contain an `if` with a condition that is a constant."})

(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'debugger.core/break}
  :within-depth 7
  :reason "The `break` macro commonly expands to contain an `if` with a condition that is a constant."})

(disable-warning
 {:linter :constant-test
  :for-macro 'clojure.core/while
  :if-inside-macroexpansion-of #{'clojure.core/let}
  :within-depth 5
  :reason "`while` macroexpands to an `if` clause without the else part, so warning about it is redundant."})

(disable-warning
 {:linter :suspicious-expression
  ;; specifically, those detected in function suspicious-macro-invocations
  :for-macro 'clojure.core/let
  :if-inside-macroexpansion-of #{'clojure.core/when-first}
  :within-depth 6
  :reason "when-first with an empty body is warned about, so warning about let with an empty body in its macroexpansion is redundant."})

(disable-warning
 {:linter :suspicious-expression
  ;; specifically, those detected in function suspicious-macro-invocations
  :for-macro 'clojure.core/let
  :if-inside-macroexpansion-of #{'clojure.core/when-let}
  :within-depth 6
  :reason "when-let with an empty body is warned about, so warning about let with an empty body in its macroexpansion is redundant."})

(disable-warning
 {:linter :suspicious-expression
  ;; specifically, those detected in function suspicious-macro-invocations
  :for-macro 'clojure.core/let
  :if-inside-macroexpansion-of #{'clojure.core/when-some}
  :within-depth 3
  :reason "when-some with an empty body is warned about, so warning about let with an empty body in its macroexpansion is redundant."})

(disable-warning
  {:linter :suspicious-expression
   :for-macro 'clojure.core/and
   :if-inside-macroexpansion-of #{'clojure.spec/every 'clojure.spec.alpha/every
                                  'clojure.spec/and 'clojure.spec.alpha/and}
   :within-depth 6
   :reason "clojure.spec's macros `every` and `and` often contain `clojure.core/and` invocations with only one argument."})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"public final void java.lang.Thread.stop\(\)"}})

(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'clojure.tools.nrepl.load-file-test/eastwood-ignore-unused-ret}
  :within-depth 1
  :reason "The macro wraps expressions that are used solely for side-effects in the repl session."})
