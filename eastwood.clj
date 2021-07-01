(disable-warning
 {:linter :deprecations
  :symbol-matches #{#"public final void java.lang.Thread.stop\(\)"}})

(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'nrepl.core-test/when-require}})
