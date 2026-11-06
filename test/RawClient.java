import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tiny HTTP client on a raw socket. We use raw sockets (not HttpClient) so the
 * tests can send exactly the bytes we want: lowercase headers, pipelined
 * requests, broken framing, slow clients and so on.
 */
public class RawClient implements AutoCloseable {
    final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RawClient(int port) throws IOException {
        this(port, 5000);
    }

    RawClient(int port, int readTimeoutMs) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        socket.setSoTimeout(readTimeoutMs);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** Sends text as ISO-8859-1 (one byte per char), which is what HTTP headers use. */
    RawClient send(String text) throws IOException {
        return send(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    RawClient send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
        return this;
    }

    Response readResponse() throws IOException {
        return readResponse(false);
    }

    /** Reads one response. For a HEAD request there is no body even if Content-Length is set. */
    Response readResponse(boolean isHeadRequest) throws IOException {
        Response response = new Response();
        response.statusLine = readLine();
        if (response.statusLine == null) {
            throw new IOException("Server closed the connection without a response");
        }
        response.status = Integer.parseInt(response.statusLine.split(" ")[1]);
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            response.headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String length = response.header("Content-Length");
        if (!isHeadRequest && length != null) {
            int n = Integer.parseInt(length);
            response.body = in.readNBytes(n);
            if (response.body.length != n) {
                throw new IOException("Body ended early: got " + response.body.length + " of " + n + " bytes");
            }
        }
        return response;
    }

    /** True if the server has closed the connection (EOF or reset). Waits up to the read timeout. */
    boolean serverClosed() throws IOException {
        try {
            return in.read() == -1;
        } catch (SocketException e) {
            return true; // a reset also means the server closed it
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /** Sends one raw request on a new connection and reads the response. */
    static Response exchange(int port, String rawRequest) throws IOException {
        try (RawClient client = new RawClient(port)) {
            return client.send(rawRequest).readResponse();
        }
    }

    static class Response {
        String statusLine;
        int status;
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] body = new byte[0];

        String header(String name) {
            return headers.get(name);
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return statusLine + " " + headers;
        }
    }
}
