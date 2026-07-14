package nrepl.in;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

/**
 * A PushbackReader that records every character it reads into an internal
 * buffer (removing it again on unread), so that the exact text consumed by a
 * read can be recovered afterwards via {@link #getCaptured()}. Used by the
 * built-in command-line client to find a form's boundary while keeping the
 * original input text to send to the server verbatim.
 *
 * <p>Only single-character {@code read()}/{@code unread(int)} are capture-aware
 * (that is all Clojure's LispReader uses). The bulk {@code read(char[], ...)}
 * methods route through {@code read()} so they stay consistent, but the bulk
 * {@code unread(char[])} methods are unsupported rather than silently
 * corrupting the capture.
 */
public class CapturingPushbackReader extends PushbackReader {
    private final StringBuilder captured = new StringBuilder();

    public CapturingPushbackReader(Reader in) {
        super(in);
    }

    /** Returns the text consumed since the last {@link #clearCaptured()}. */
    public String getCaptured() {
        return captured.toString();
    }

    /** Discards the captured text, e.g. before reading the next form. */
    public void clearCaptured() {
        captured.setLength(0);
    }

    @Override
    public int read() throws IOException {
        int c = super.read();
        if (c != -1) {
            captured.append((char) c);
        }
        return c;
    }

    @Override
    public void unread(int c) throws IOException {
        int len = captured.length();
        if (len > 0) {
            captured.setLength(len - 1);
        }
        super.unread(c);
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        for (int n = 0; n < len; n++) {
            int c = read();
            if (c == -1) {
                return n == 0 ? -1 : n;
            }
            cbuf[off + n] = (char) c;
        }
        return len;
    }

    @Override
    public void unread(char[] cbuf, int off, int len) {
        throw new UnsupportedOperationException("bulk unread is not capture-aware");
    }

    @Override
    public void unread(char[] cbuf) {
        throw new UnsupportedOperationException("bulk unread is not capture-aware");
    }
}
