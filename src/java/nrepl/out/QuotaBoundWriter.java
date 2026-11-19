package nrepl.out;

import java.io.*;

/**
 * Wrapper around <code>java.io.Writer</code> that throws
 * <code>nrepl.out.QuotaExceeded</code> once it has written more than quota
 * characters and if throwOnExceeded is set to true (otherwise, only stop writing
 * when quota is reached).
 */
public class QuotaBoundWriter extends Writer {

    private final Writer wrappedWriter;
    private final int quota;
    private final boolean throwOnExceeded;
    private int remaining;

    public QuotaBoundWriter(Writer writer, int quota, boolean throwOnExceeded) {
        super();
        if (quota <= 0) {
            throw new IllegalArgumentException("Invalid quota: " + quota);
        }
        this.wrappedWriter = writer;
        this.quota = quota;
        this.remaining = quota;
        this.throwOnExceeded = throwOnExceeded;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        synchronized (this.lock) {
            boolean tooBig = (len > remaining);
            len = Math.min(len, remaining);
            if (len > 0) {
                wrappedWriter.write(cbuf, off, len);
                remaining -= len;
            }
            if (tooBig && throwOnExceeded)
                throw new QuotaExceeded();
        }
    }

    @Override
    public void flush() throws IOException {
        wrappedWriter.flush();
    }

    @Override
    public void close() throws IOException {
        wrappedWriter.close();
    }

    @Override
    public String toString() {
        return wrappedWriter.toString();
    }
}
