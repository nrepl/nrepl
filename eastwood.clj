(disable-warning
 {:linter :constant-test
  :for-macro 'clojure.core/while
  :if-inside-macroexpansion-of #{'clojure.core/let}
  :within-depth 5
  :reason "`while` macroexpands to an `if` clause without the else part, so warning about it is redundant."})

(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"public final void java.lang.Thread.stop\(\)"}})
