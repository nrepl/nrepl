package cemerick.nrepl;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

public class main {
    public static void main (String[] args) throws Exception {
        RT.var("clojure.core", "require").invoke(Symbol.intern("cemerick.nrepl.cmdline"));
        RT.var("cemerick.nrepl.cmdline", "main").applyTo(RT.seq(args));
    }
}
