package nrepl.out;

import clojure.lang.IFn;
import java.io.*;
import java.nio.charset.Charset;

/**
 * An OutputStream that buffers incoming bytes and sends complete lines
 * (ending with newline) to a network sender. Incomplete lines are held
 * in buffer until completed.
 */
public class CallbackBufferedOutputStream extends OutputStream {
    private final IFn callback;
    private final ByteArrayOutputStream buffer;
    private final int bufferSize;
    private final Charset charset;
    private final Object bufferLock = new Object();

    public CallbackBufferedOutputStream(IFn callback, int bufferSize) {
        this.callback = callback;
        this.bufferSize = bufferSize;
        this.charset = Charset.defaultCharset();
        this.buffer = new ByteArrayOutputStream();
    }

    @Override
    public void write(int b) throws IOException {
        synchronized (bufferLock) {
            buffer.write(b);
            if (b == '\n' || buffer.size() >= bufferSize)
                maybeFlush(false);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        synchronized (bufferLock) {
            int end = off + len;
            while (off < end) {
                int writeLen = Math.min(len, bufferSize - buffer.size());
                buffer.write(b, off, writeLen);
                maybeFlush(false);
                off += writeLen;
                len -= writeLen;
            }
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (bufferLock) {
            maybeFlush(true);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (bufferLock) {
            maybeFlush(true);
        }
    }

    /**
     * Invoke callback with the text accumulated in the buffer if it contains
     * newlines or went beyond buffer size or force is true.
     */
    private void maybeFlush(boolean force) throws IOException {
        int size = buffer.size();
        if (size == 0) return;

        String content = buffer.toString(charset.name());
        int lastNewlineIndex = content.lastIndexOf('\n');
        int lengthToFlush = (force || size == bufferSize) ? size : lastNewlineIndex + 1;
        if (lengthToFlush > 0) {
            String flushContent = content.substring(0, lengthToFlush);
            callback.invoke(flushContent);
            buffer.reset();
            if (lengthToFlush < size) {
                String remaining = content.substring(lengthToFlush + 1);
                buffer.write(remaining.getBytes(charset));
            }
        }
    }
}
