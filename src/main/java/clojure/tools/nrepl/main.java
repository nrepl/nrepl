package clojure.tools.nrepl;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

/**
 * @author Chas Emerick
 */
public class main {
    public static void main (String[] args) throws Exception {
        RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.tools.nrepl.cmdline"));
        RT.var("clojure.tools.nrepl.cmdline", "main").applyTo(RT.seq(args));
    }
}
