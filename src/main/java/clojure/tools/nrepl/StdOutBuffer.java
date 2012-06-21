package clojure.tools.nrepl;

/**
 * This class exists solely so that the clojure side can call .setLength under JDK 1.5.
 * Doing so with a StringBuilder/StringBuffer fails with:
 *
 * Can't call public method of non-public class: public void java.lang.AbstractStringBuilder.setLength(int)
 *
 * ...as documented in these outstanding bugs:
 * http://dev.clojure.org/jira/browse/CLJ-126
 * http://dev.clojure.org/jira/browse/CLJ-259
 */
public class StdOutBuffer {
    private final StringBuilder sb = new StringBuilder();

    public void setLength (int x) {
        sb.setLength(x);
    }

    public int length () {
        return sb.length();
    }

    public void append(Object x) {
        sb.append(x);
    }

    public void append(char x) {
        sb.append(x);
    }

    public void append(CharSequence s, int start, int end) {
        sb.append(s, start, end);
    }

    public void append(CharSequence s) {
        sb.append(s);
    }

    public void append(char[] s, int start, int end) {
        sb.append(s, start, end);
    }

    public void append(char[] s) {
        sb.append(s);
    }

    public String toString() {
        return sb.toString();
    }
}
