package cemerick.nrepl;

import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.RT;

public class SafeFn implements IFn {
    private IFn f;

    private SafeFn (IFn f) {
        this.f = f;
    }
    
    public static SafeFn wrap (IFn f) {
        return new SafeFn(f);
    }
    
    public static SafeFn find (String ns, String name) {
        return new SafeFn(RT.var(ns, name));
    }

    public Object applyTo(ISeq arg0) {
        try {
            return f.applyTo(arg0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object call() {
        try {
            return f.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke() {
        try {
            return f.invoke();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16,
            Object arg17, Object arg18, Object arg19, Object... arg20) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                    arg16, arg17, arg18, arg19, arg20);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16,
            Object arg17, Object arg18, Object arg19) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                    arg16, arg17, arg18, arg19);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16,
            Object arg17, Object arg18) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                    arg16, arg17, arg18);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16, Object arg17) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15,
                    arg16, arg17);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13, arg14);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12, arg13);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3, arg4);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2, Object arg3) {
        try {
            return f.invoke(arg0, arg1, arg2, arg3);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1, Object arg2) {
        try {
            return f.invoke(arg0, arg1, arg2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0, Object arg1) {
        try {
            return f.invoke(arg0, arg1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object sInvoke(Object arg0) {
        try {
            return f.invoke(arg0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {
        f.run();
    }

    public Object invoke() throws Exception {
        return f.invoke();
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16,
            Object arg17, Object arg18, Object arg19, Object... arg20)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17,
                arg18, arg19, arg20);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16,
            Object arg17, Object arg18, Object arg19) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17,
                arg18, arg19);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16,
            Object arg17, Object arg18) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17,
                arg18);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16, Object arg17)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15, Object arg16)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14, Object arg15) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14, arg15);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12,
            Object arg13, Object arg14) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13, arg14);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12, Object arg13)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12, arg13);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11, Object arg12)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10, Object arg11) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9, Object arg10) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8,
            Object arg9) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7, Object arg8)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6, Object arg7)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5, Object arg6) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4, Object arg5) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4, arg5);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3,
            Object arg4) throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3, arg4);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3)
            throws Exception {
        return f.invoke(arg0, arg1, arg2, arg3);
    }

    public Object invoke(Object arg0, Object arg1, Object arg2)
            throws Exception {
        return f.invoke(arg0, arg1, arg2);
    }

    public Object invoke(Object arg0, Object arg1) throws Exception {
        return f.invoke(arg0, arg1);
    }

    public Object invoke(Object arg0) throws Exception {
        return f.invoke(arg0);
    }

    
}
