package clojure.tools.nrepl;

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
import clojure.lang.Symbol;
import clojure.lang.Var;

/**
 * @author Chas Emerick
 */
public class Connection implements Closeable {
    static {
        try {
            RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.tools.nrepl"));
            RT.var("clojure.core", "require").invoke(Symbol.intern("clojure.walk"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Var find (String ns, String name) {
        return Var.find(Symbol.intern(ns, name));
    }
    
    private static Var connect = find("clojure.tools.nrepl", "connect"),
        urlConnect = find("clojure.tools.nrepl", "url-connect"),
        createClient = find("clojure.tools.nrepl", "client"),
        session = find("clojure.tools.nrepl", "session"),
        message = find("clojure.tools.nrepl", "message"),
        combineResponses = find("clojure.tools.nrepl", "combine-responses"),
        map = find("clojure.core", "map"),
        readString = find("clojure.core", "read-string"),
        stringifyKeys = find("clojure.walk", "stringify-keys");
    
    public final Closeable transport;
    public final IFn client;
    public final String url;
    
    public Connection (String url) throws Exception {
        transport = (Closeable)urlConnect.invoke(this.url = url);
        client = (IFn)createClient.invoke(transport, Long.MAX_VALUE);
    }
    
    public void close () throws IOException {
        transport.close();
    }
    
    public Response send (String... kvs) {
        try {
            return new Response((ISeq)message.invoke(client, PersistentHashMap.createWithCheck(kvs)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public Response sendSession (String session, String... kvs) {
        try {
            return new Response((ISeq)message.invoke(
                    Connection.this.session.invoke(client, Keyword.intern("session"), session), PersistentHashMap.createWithCheck(kvs)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static class Response {
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
    }
    
    
}
