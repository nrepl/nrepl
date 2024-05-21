package nrepl;

/**
 * Java facade for the C side of the libnrepl JVMTI agent. Currently used to
 * stop threads on JDK20+.
 */
public class JvmtiAgent {

    // This will bind to Java_nrepl_JvmtiAgent_stopThread function once the
    // agent containing this function will be attached.
    public static native void stopThread(Thread thread, Throwable throwable);

    /**
     * Forcibly stop a given thread.
     */
    public static void stopThread(Thread thread) {
        // ThreadDeath is deprecated, but so it Thread.stop(). We can revisit
        // this when JVM actually decides to remove this class.
        @SuppressWarnings("deprecation")
        Throwable throwable = new ThreadDeath();
        throwable.setStackTrace(new StackTraceElement[0]);
        stopThread(thread, throwable);
    }
}
