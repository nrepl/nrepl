package nrepl;

import clojure.lang.IFn;

/**
 * A custom thread implementation that trims the eval callstack further.
 */
public class SessionThread extends Thread {

    IFn runFn;

    /**
     * Set once nREPL has given up on what the thread is running: an interrupt
     * has been answered on its behalf and another thread has taken over the
     * session.
     */
    public volatile boolean abandoned = false;

    public SessionThread(IFn runFn, String name, ClassLoader classLoader) {
        this.runFn = runFn;
        setName(name);
        setContextClassLoader(classLoader);
        setDaemon(true);
    }

    @Override
    public void run() {
        runFn.invoke();
    }
}
