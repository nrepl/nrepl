package nrepl;

import clojure.lang.IFn;
import java.io.*;

/**
 * A variant of StringWriter that invokes the provided callback on the printed
 * string every time something is written to the writer.
 */
public class CallbackWriter extends StringWriter {

    private final IFn callback;

    public CallbackWriter(IFn callback) {
        super();
        this.callback = callback;
    }

    @Override
    public void write(char[] cbuf) {
        this.write(cbuf, 0, cbuf.length);
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        this.write(new String(cbuf, off, len), off, len);
    }

    @Override
    public void write(String str) {
        this.write(str, 0, str.length());
    }

    @Override
    public void write(String str, int off, int len) {
        String s = off == 0 && len == str.length() ?
            str : str.substring(off, off+len);
        if (len > 0)
            callback.invoke(s);
    }
}
