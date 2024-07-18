package nrepl;

import java.io.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wrapper around <code>java.io.Writer</code> that throws
 * <code>nrepl.QuotaExceeded</code> once it has written more than quota bytes.
 */
public class QuotaBoundWriter extends Writer {

    private final Writer wrappedWriter;
    private final int quota;
    private int remaining;
    private final ReentrantLock lock = new ReentrantLock();

    public QuotaBoundWriter(Writer writer, int quotaBytes) {
        super();
        if (quotaBytes <= 0) {
            throw new IllegalArgumentException("Invalid quota: " + quotaBytes);
        }
        this.wrappedWriter = writer;
        this.quota = quotaBytes;
        this.remaining = quotaBytes;
    }

    @Override
    public void write(int c) throws IOException {
        lock.lock();
        try {
            if (remaining <= 0)
                throw new QuotaExceeded();
            wrappedWriter.write(c);
            remaining--;
        } finally {
            lock.unlock();
        }
    }

    private void writeStringOrChars(Object stringOrChars, int off, int len)
        throws IOException {
        lock.lock();
        try {
            boolean tooBig = (len > remaining);
            len = Math.min(len, remaining);
            if (len > 0) {
                if (stringOrChars instanceof String)
                    wrappedWriter.write((String)stringOrChars, off, len);
                else
                    wrappedWriter.write((char[])stringOrChars, off, len);
                remaining -= len;
            }
            if (tooBig)
                throw new QuotaExceeded();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void write(char[] cbuf) throws IOException {
        this.write(cbuf, 0, cbuf.length);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        writeStringOrChars(cbuf, off, len);
    }

    @Override
    public void write(String str) throws IOException {
        this.write(str, 0, str.length());
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        writeStringOrChars(str, off, len);
    }

    @Override
    public String toString() {
        return wrappedWriter.toString();
    }

    @Override
    public void flush() throws IOException {
        wrappedWriter.flush();
    }

    @Override
    public void close() throws IOException {
        wrappedWriter.close();
    }
}
