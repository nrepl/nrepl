package cemerick.nrepl;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

public class main {
    public static void main (String[] args) throws Exception {
        Object initClojure = RT.OUT.deref();
        
        Var.find(Symbol.intern("clojure.core/require")).invoke(Symbol.intern("cemerick.nrepl.cmdline"));
        Var.find(Symbol.intern("cemerick.nrepl.cmdline/main")).applyTo(RT.seq(args));
    }
}
