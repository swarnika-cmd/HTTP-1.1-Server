import java.util.Map;
import java.util.TreeMap;

/** A parsed HTTP request: request line, headers and (optional) body. */
public class HttpRequest {
    final String method;
    final String target;   // exactly as sent, e.g. "/about.html?x=1"
    final String version;  // "HTTP/1.0" or "HTTP/1.1"

    // Header names are case-insensitive (RFC 7230 section 3.2), so
    // headers.get("content-length") and headers.get("Content-Length") are the same.
    final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    // How the body is framed, filled in by the parser
    long contentLength = 0;
    boolean chunked = false;

    byte[] body = new byte[0];

    HttpRequest(String method, String target, String version) {
        this.method = method;
        this.target = target;
        this.version = version;
    }

    String header(String name) {
        return headers.get(name);
    }

    boolean isHttp11() {
        return "HTTP/1.1".equals(version);
    }

    /** The path part of the target, without the query string. Still percent-encoded. */
    String rawPath() {
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    /** True if the Connection header lists the given option, e.g. "close" or "keep-alive". */
    boolean hasConnectionOption(String option) {
        String value = headers.get("Connection");
        if (value == null) return false;
        for (String token : value.split(",")) {
            if (token.trim().equalsIgnoreCase(option)) return true;
        }
        return false;
    }

    /**
     * HTTP/1.1 connections stay open unless the client sends "Connection: close".
     * HTTP/1.0 connections close unless the client sends "Connection: keep-alive".
     */
    boolean wantsKeepAlive() {
        if (isHttp11()) {
            return !hasConnectionOption("close");
        }
        return hasConnectionOption("keep-alive");
    }
}
