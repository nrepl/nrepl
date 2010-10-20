package cemerick.nrepl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import clojure.lang.AFn;
import clojure.lang.Delay;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

public class Connection {    
    static {
        Object initClojure = RT.OUT;
        try {
            RT.var("clojure.core", "require").invoke(Symbol.intern("cemerick.nrepl"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private static SafeFn connect = SafeFn.find("cemerick.nrepl", "connect"),
        readResponseValue = SafeFn.find("cemerick.nrepl", "read-response-value"),
        combineResponses = SafeFn.find("cemerick.nrepl", "combine-responses"),
        responseSeq = SafeFn.find("cemerick.nrepl", "response-seq"),
        evalResponse = SafeFn.find("cemerick.nrepl", "eval-response"),
        map = SafeFn.find("clojure.core", "map"),
        readString = SafeFn.find("clojure.core", "read-string");
    
    public final Map<Keyword, IFn> conn;
    private final SafeFn send, close;
    public final String host;
    public final int port;
    
    @SuppressWarnings("unchecked")
    public Connection (String host, int port) throws Exception {
        this.host = host;
        this.port = port;
        conn = (Map<Keyword, IFn>)connect.invoke(host, port);
        send = SafeFn.wrap(conn.get(Keyword.intern("send")));
        close = SafeFn.wrap(conn.get(Keyword.intern("close")));
    }
    
    public Response send (String code) {
        return new Response(SafeFn.wrap((IFn)send.sInvoke(code)));
    }
    
    public void close () {
        close.sInvoke();
    }
    
    public static class Response {
        public final SafeFn responseFn;
        private final Delay combinedResponse;
        
        private Response (SafeFn responseFn) {
            this.responseFn = responseFn;
            combinedResponse = new Delay(new AFn () {
               public Object invoke () throws Exception {
                   return combineResponses.invoke(responseSeq.invoke(Response.this.responseFn));
               }
            });
        }
        
        public Map<Keyword, Object> combinedResponse () {
            try {
                return (Map)combinedResponse.deref();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        public Set<String> statuses () {
            try {
                return (Set<String>)combinedResponse().get(Keyword.intern("status"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        public List<Object> values () {
            return (List<Object>)map.sInvoke(readString, combinedResponse().get(Keyword.intern("value")));
        }
    }
    
    
}
