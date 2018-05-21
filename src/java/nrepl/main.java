package nrepl;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

/**
 * @author Chas Emerick
 */
public class main {
    public static void main (String[] args) throws Exception {
        RT.var("clojure.core", "require").invoke(Symbol.intern("nrepl.cmdline"));
        RT.var("nrepl.cmdline", "-main").applyTo(RT.seq(args));
    }
}
