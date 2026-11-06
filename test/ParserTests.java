import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/** Unit tests for the parser and helpers. No sockets, so these are fast. */
public class ParserTests {

    private static final HttpRequestParser PARSER = new HttpRequestParser(8192, 100, 1024);

    static void register() {
        TestRunner.test("parser: request line and case-insensitive headers", () -> {
            HttpRequest r = parse("GET /a?b=1 HTTP/1.1\r\nHOST: example\r\ncontent-LENGTH: 0\r\n\r\n");
            TestRunner.assertEquals("GET", r.method, "method");
            TestRunner.assertEquals("/a?b=1", r.target, "target");
            TestRunner.assertEquals("/a", r.rawPath(), "path without query");
            TestRunner.assertEquals("example", r.header("Host"), "Host looked up with different case");
            TestRunner.assertEquals("0", r.header("Content-Length"), "Content-Length looked up with different case");
        });

        TestRunner.test("parser: leading empty lines and bare LF are accepted", () -> {
            HttpRequest r = parse("\r\n\nGET / HTTP/1.0\nHost: x\n\n");
            TestRunner.assertEquals("HTTP/1.0", r.version, "version");
            TestRunner.assertEquals("x", r.header("host"), "host");
        });

        TestRunner.test("parser: body is read as exactly Content-Length bytes", () -> {
            byte[] body = "{\"price\":\"₹10\"}".getBytes(UTF_8); // ₹ is 3 bytes in UTF-8
            byte[] head = ("POST /upload HTTP/1.1\r\nHost: x\r\ncontent-length: " + body.length + "\r\n\r\n")
                .getBytes(ISO_8859_1);
            InputStream in = new ByteArrayInputStream(concat(head, body, "NEXT".getBytes(ISO_8859_1)));
            HttpRequest r = PARSER.readHead(in);
            PARSER.readBody(in, r);
            TestRunner.assertEquals(new String(body, UTF_8), new String(r.body, UTF_8), "body");
            TestRunner.assertEquals("NEXT", new String(in.readAllBytes(), ISO_8859_1), "bytes left for next request");
        });

        TestRunner.test("parser: chunked body with extensions and trailers", () -> {
            String raw = "POST /upload HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4;name=value\r\nWiki\r\n"
                + "6\r\npedia \r\n"
                + "E\r\nin \r\n\r\nchunks.\r\n"
                + "0\r\nX-Trailer: ignored\r\n\r\n"
                + "NEXT";
            InputStream in = new ByteArrayInputStream(raw.getBytes(ISO_8859_1));
            HttpRequest r = PARSER.readHead(in);
            PARSER.readBody(in, r);
            TestRunner.assertEquals("Wikipedia in \r\n\r\nchunks.", new String(r.body, ISO_8859_1), "decoded body");
            TestRunner.assertEquals("NEXT", new String(in.readAllBytes(), ISO_8859_1), "bytes left for next request");
        });

        TestRunner.test("parser: bad chunk size and missing CRLF are 400", () -> {
            TestRunner.assertThrowsStatus(400, () -> parseWithBody(
                "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nabc\r\n0\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parseWithBody(
                "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcX\r\n0\r\n\r\n"));
        });

        TestRunner.test("parser: chunked body larger than the limit is 413", () ->
            TestRunner.assertThrowsStatus(413, () -> parseWithBody(
                "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "400\r\n" + "a".repeat(1024) + "\r\n1\r\nb\r\n0\r\n\r\n")));

        TestRunner.test("parser: Content-Length validation", () -> {
            TestRunner.assertEquals(10L, HttpRequestParser.parseContentLength("10"), "plain number");
            TestRunner.assertEquals(5L, HttpRequestParser.parseContentLength("5, 5"), "identical duplicates");
            TestRunner.assertThrowsStatus(400, () -> HttpRequestParser.parseContentLength("-1"));
            TestRunner.assertThrowsStatus(400, () -> HttpRequestParser.parseContentLength("+5"));
            TestRunner.assertThrowsStatus(400, () -> HttpRequestParser.parseContentLength("abc"));
            TestRunner.assertThrowsStatus(400, () -> HttpRequestParser.parseContentLength(""));
            TestRunner.assertThrowsStatus(400, () -> HttpRequestParser.parseContentLength("5, 6"));
            TestRunner.assertThrowsStatus(413, () -> HttpRequestParser.parseContentLength("99999999999999999999"));
        });

        TestRunner.test("parser: Content-Length together with Transfer-Encoding is 400", () ->
            TestRunner.assertThrowsStatus(400, () -> parse(
                "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n")));

        TestRunner.test("parser: unsupported Transfer-Encoding is 501", () ->
            TestRunner.assertThrowsStatus(501, () -> parse(
                "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip, chunked\r\n\r\n")));

        TestRunner.test("parser: malformed header lines are 400", () -> {
            TestRunner.assertThrowsStatus(400, () -> parse("GET / HTTP/1.1\r\nHost : x\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET / HTTP/1.1\r\nHost: x\r\n folded\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET / HTTP/1.1\r\nno colon here\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET / HTTP/1.1\r\nHost: a\rb\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"));
        });

        TestRunner.test("parser: request line errors (400, 505)", () -> {
            TestRunner.assertThrowsStatus(400, () -> parse("GET /\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET  / HTTP/1.1\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET / HTTX/1.1\r\n\r\n"));
            TestRunner.assertThrowsStatus(400, () -> parse("GET index.html HTTP/1.1\r\n\r\n"));
            TestRunner.assertThrowsStatus(505, () -> parse("GET / HTTP/2.0\r\n\r\n"));
            TestRunner.assertThrowsStatus(505, () -> parse("GET / HTTP/0.9\r\n\r\n"));
        });

        TestRunner.test("parser: absolute-form target is reduced to the path", () -> {
            TestRunner.assertEquals("/about.html?x=1",
                parse("GET http://example.com/about.html?x=1 HTTP/1.1\r\nHost: x\r\n\r\n").target, "target");
            TestRunner.assertEquals("/", parse("GET http://example.com HTTP/1.1\r\nHost: x\r\n\r\n").target, "target");
        });

        TestRunner.test("parser: header size and count limits (414, 431)", () -> {
            TestRunner.assertThrowsStatus(414, () -> parse("GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n"));
            TestRunner.assertThrowsStatus(431, () -> parse("GET / HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n"));
            StringBuilder many = new StringBuilder("GET / HTTP/1.1\r\n");
            for (int i = 0; i < 101; i++) many.append("X-").append(i).append(": 1\r\n");
            TestRunner.assertThrowsStatus(431, () -> parse(many + "\r\n"));
        });

        TestRunner.test("parser: empty stream returns null (client closed)", () ->
            TestRunner.assertEquals(null, PARSER.readHead(new ByteArrayInputStream(new byte[0])), "result"));

        TestRunner.test("request: keep-alive defaults for HTTP/1.0 and HTTP/1.1", () -> {
            TestRunner.assertEquals(true, parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n").wantsKeepAlive(), "1.1 default");
            TestRunner.assertEquals(false,
                parse("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").wantsKeepAlive(), "1.1 + close");
            TestRunner.assertEquals(false, parse("GET / HTTP/1.0\r\n\r\n").wantsKeepAlive(), "1.0 default");
            TestRunner.assertEquals(true,
                parse("GET / HTTP/1.0\r\nConnection: Keep-Alive\r\n\r\n").wantsKeepAlive(), "1.0 + keep-alive");
            TestRunner.assertEquals(false,
                parse("GET / HTTP/1.1\r\nHost: x\r\nConnection: upgrade, Close\r\n\r\n").wantsKeepAlive(),
                "close listed with other options");
        });

        TestRunner.test("static: percent-decoding of paths", () -> {
            TestRunner.assertEquals("/about.html", StaticFileHandler.percentDecode("/%61bout.html"), "%61");
            TestRunner.assertEquals("/a+b c", StaticFileHandler.percentDecode("/a+b%20c"), "plus stays plus");
            TestRunner.assertEquals("/₹", StaticFileHandler.percentDecode("/%E2%82%B9"), "UTF-8");
            TestRunner.assertEquals("/../x", StaticFileHandler.percentDecode("/%2e%2e/x"), "encoded dots");
            for (String bad : new String[] {"/%zz", "/%4", "/%C3", "/a%00b"}) {
                try {
                    StaticFileHandler.percentDecode(bad);
                    throw new AssertionError("expected failure for " + bad);
                } catch (IllegalArgumentException expected) {
                    // ok
                }
            }
        });

        TestRunner.test("config: env vars and flags (flags win)", () -> {
            ServerConfig c = ServerConfig.load(
                Map.of("PORT", "9000", "SERVER_MODE", "virtual", "THREADS", "8"),
                new String[] {"--threads=4", "--queue=16", "--upload-dir=/tmp/up"});
            TestRunner.assertEquals(9000, c.port, "port from env");
            TestRunner.assertEquals("0.0.0.0", c.host, "PORT env binds all interfaces");
            TestRunner.assertEquals(true, c.isVirtualMode(), "mode from env");
            TestRunner.assertEquals(4, c.threads, "flag overrides env");
            TestRunner.assertEquals(16, c.queueCapacity, "queue");
            TestRunner.assertEquals(Path.of("/tmp/up"), c.uploadDir, "upload dir");
            try {
                ServerConfig.load(Map.of(), new String[] {"--mode=green"});
                throw new AssertionError("invalid mode accepted");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        });
    }

    private static HttpRequest parse(String raw) throws Exception {
        return PARSER.readHead(new ByteArrayInputStream(raw.getBytes(ISO_8859_1)));
    }

    private static HttpRequest parseWithBody(String raw) throws Exception {
        InputStream in = new ByteArrayInputStream(raw.getBytes(ISO_8859_1));
        HttpRequest r = PARSER.readHead(in);
        PARSER.readBody(in, r);
        return r;
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length += p.length;
        byte[] result = new byte[length];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, pos, p.length);
            pos += p.length;
        }
        return result;
    }
}
