package nrepl;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import clojure.lang.AFn;
import clojure.lang.ArraySeq;
import clojure.lang.Delay;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.RT;
import clojure.lang.Seqable;
import clojure.lang.Symbol;
import clojure.lang.Var;

/**
 * @author Chas Emerick
 */
public class Connection implements Closeable {
    static {
        try {
            RT.var("clojure.core", "require").invoke(Symbol.intern("nrepl.core"));
            RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.walk"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Var find (String ns, String name) {
        return Var.find(Symbol.intern(ns, name));
    }

    private static Var connect = find("nrepl.core", "connect"),
        urlConnect = find("nrepl.core", "url-connect"),
        createClient = find("nrepl.core", "client"),
        clientSession = find("nrepl.core", "client-session"),
        newSession = find("nrepl.core", "new-session"),
        message = find("nrepl.core", "message"),
        combineResponses = find("nrepl.core", "combine-responses"),
        map = find("clojure.core", "map"),
        readString = find("clojure.core", "read-string"),
        stringifyKeys = find("clojure.walk", "stringify-keys");

    public final Closeable transport;
    public final IFn client;
    public final String url;

    public Connection (String url) throws Exception {
        this(url, Long.MAX_VALUE);
    }

    public Connection (String url, long readTimeout) throws Exception {
        transport = (Closeable)urlConnect.invoke(this.url = url);
        client = (IFn)createClient.invoke(transport, readTimeout);
    }

    public void close () throws IOException {
        transport.close();
    }

    public Response send (String... kvs) {
        try {
            Map msg = PersistentHashMap.createWithCheck(kvs);
            return new Response((ISeq)message.invoke(client, msg));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Response sendSession (String session, String... kvs) {
        try {
            Map msg = PersistentHashMap.createWithCheck(kvs);
            return new Response((ISeq)message.invoke(
                    clientSession.invoke(client, Keyword.intern("session"), session), msg));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String newSession (String cloneSessionId) {
        try {
            if (cloneSessionId == null) {
                return (String)newSession.invoke(client);
            } else {
                return (String)newSession.invoke(client, Keyword.intern("clone"), cloneSessionId);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static class Response implements Seqable {
        // would prefer to use a Delay here, but the change in IFn.invoke signatures between
        // Clojure 1.2 and 1.3 makes it impossible to be compatible with both from Java
        private ISeq responses;
        private Map<String, Object> response;

        private Response (final ISeq responses) {
            this.responses = responses;
        }

        public synchronized Map<String, Object> combinedResponse () {
            try {
                if (response == null) {
                    response = (Map<String, Object>)stringifyKeys.invoke(combineResponses.invoke(responses));
                    responses = null;
                }
                return response;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Set<String> statuses () {
            try {
                return (Set<String>)combinedResponse().get("status");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public List<Object> values () {
            try {
                return (List<Object>)map.invoke(readString, combinedResponse().get("value"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public ISeq seq() {
            return responses;
        }
    }


}
