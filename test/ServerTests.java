import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * End-to-end tests: start a real server on a random free port in this JVM and
 * talk to it over raw sockets. Timeouts are made short so the suite stays fast.
 */
public class ServerTests {
    private static final Path WEB_ROOT = Path.of("resources");
    private static final Pattern HTTP_DATE =
        Pattern.compile("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");

    private static Path uploadDir;
    private static Server shared;
    private static int port;

    static void register() {
        TestRunner.test("server: start shared test server", () -> {
            uploadDir = Files.createTempDirectory("http11-uploads");
            shared = startServer(c -> { });
            port = shared.getPort();
        });

        // ------------------------------------------------------------ basics

        TestRunner.test("GET / returns index.html with correct headers", () -> {
            RawClient.Response r = RawClient.exchange(port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            byte[] expected = Files.readAllBytes(WEB_ROOT.resolve("index.html"));
            TestRunner.assertEquals(200, r.status, "status");
            TestRunner.assertTrue(java.util.Arrays.equals(expected, r.body), "body equals resources/index.html");
            TestRunner.assertEquals(String.valueOf(expected.length), r.header("Content-Length"), "Content-Length");
            TestRunner.assertEquals("text/html; charset=utf-8", r.header("Content-Type"), "Content-Type");
            TestRunner.assertTrue(HTTP_DATE.matcher(r.header("Date")).matches(), "Date is IMF-fixdate: " + r.header("Date"));
            TestRunner.assertEquals(HttpResponseBuilder.SERVER_NAME, r.header("Server"), "Server header");
            TestRunner.assertEquals("keep-alive", r.header("Connection"), "Connection header");
        });

        TestRunner.test("GET binary file returns exact bytes as attachment", () -> {
            RawClient.Response r = RawClient.exchange(port, "GET /logo.png HTTP/1.1\r\nHost: x\r\n\r\n");
            byte[] expected = Files.readAllBytes(WEB_ROOT.resolve("logo.png"));
            TestRunner.assertEquals(200, r.status, "status");
            TestRunner.assertEquals("image/png", r.header("Content-Type"), "Content-Type");
            TestRunner.assertTrue(java.util.Arrays.equals(expected, r.body), "PNG bytes match (" + expected.length + " bytes)");
            TestRunner.assertEquals("attachment; filename=\"logo.png\"", r.header("Content-Disposition"), "disposition");
        });

        TestRunner.test("GET missing file returns 404", () -> {
            RawClient.Response r = RawClient.exchange(port, "GET /missing.html HTTP/1.1\r\nHost: x\r\n\r\n");
            TestRunner.assertEquals(404, r.status, "status");
            TestRunner.assertTrue(r.header("Date") != null && r.header("Server") != null, "error has Date and Server");
            TestRunner.assertEquals(String.valueOf(r.body.length), r.header("Content-Length"), "Content-Length in bytes");
        });

        TestRunner.test("HEAD returns GET's headers and no body", () -> {
            try (RawClient client = new RawClient(port)) {
                client.send("HEAD /about.html HTTP/1.1\r\nHost: x\r\n\r\nGET /about.html HTTP/1.1\r\nHost: x\r\n\r\n");
                RawClient.Response head = client.readResponse(true);
                RawClient.Response get = client.readResponse();
                TestRunner.assertEquals(200, head.status, "HEAD status");
                TestRunner.assertEquals(get.header("Content-Length"), head.header("Content-Length"), "same Content-Length");
                TestRunner.assertEquals(get.header("Content-Type"), head.header("Content-Type"), "same Content-Type");
                // If HEAD had sent a body, the second response would start with HTML instead of a status line
                TestRunner.assertEquals(200, get.status, "GET after HEAD parses correctly");
            }
        });

        TestRunner.test("405 includes Allow header; unknown method is 501", () -> {
            RawClient.Response put = RawClient.exchange(port, "PUT /index.html HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
            TestRunner.assertEquals(405, put.status, "PUT status");
            TestRunner.assertEquals("GET, HEAD", put.header("Allow"), "Allow for static files");
            RawClient.Response getUpload = RawClient.exchange(port, "GET /upload HTTP/1.1\r\nHost: x\r\n\r\n");
            TestRunner.assertEquals(405, getUpload.status, "GET /upload status");
            TestRunner.assertEquals("POST", getUpload.header("Allow"), "Allow for /upload");
            RawClient.Response unknown = RawClient.exchange(port, "BREW / HTTP/1.1\r\nHost: x\r\n\r\n");
            TestRunner.assertEquals(501, unknown.status, "unknown method status");
        });

        TestRunner.test("HTTP/2.0 request line returns 505", () ->
            TestRunner.assertEquals(505, RawClient.exchange(port, "GET / HTTP/2.0\r\nHost: x\r\n\r\n").status, "status"));

        TestRunner.test("malformed request line returns 400", () -> {
            TestRunner.assertEquals(400, RawClient.exchange(port, "GET /\r\n\r\n").status, "missing version");
            TestRunner.assertEquals(400, RawClient.exchange(port, "GET / HTTP/1.1 extra\r\n\r\n").status, "4 parts");
        });

        TestRunner.test("HTTP/1.1 without Host returns 400, HTTP/1.0 without Host is fine", () -> {
            TestRunner.assertEquals(400, RawClient.exchange(port, "GET / HTTP/1.1\r\n\r\n").status, "1.1 no Host");
            TestRunner.assertEquals(200, RawClient.exchange(port, "GET / HTTP/1.0\r\n\r\n").status, "1.0 no Host");
            TestRunner.assertEquals(200,
                RawClient.exchange(port, "GET / HTTP/1.1\r\nHost: anything.example:1234\r\n\r\n").status, "any Host value");
        });

        TestRunner.test("query string is ignored and percent-encoded paths work", () -> {
            TestRunner.assertEquals(200,
                RawClient.exchange(port, "GET /about.html?x=1&y=2 HTTP/1.1\r\nHost: x\r\n\r\n").status, "query");
            TestRunner.assertEquals(200,
                RawClient.exchange(port, "GET /%61bout.html HTTP/1.1\r\nHost: x\r\n\r\n").status, "%61 = a");
            TestRunner.assertEquals(400,
                RawClient.exchange(port, "GET /%zz HTTP/1.1\r\nHost: x\r\n\r\n").status, "bad escape");
        });

        TestRunner.test("path traversal is blocked (plain and encoded)", () -> {
            String[] attacks = {"/../README.md", "/%2e%2e/README.md", "/..%2fREADME.md",
                "/%2E%2E%2F%2E%2E%2Fetc%2Fpasswd", "//etc/passwd", "/a/../../README.md"};
            for (String path : attacks) {
                RawClient.Response r = RawClient.exchange(port, "GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n");
                TestRunner.assertEquals(403, r.status, "status for " + path);
            }
        });

        TestRunner.test("absolute-form request target is accepted", () ->
            TestRunner.assertEquals(200, RawClient.exchange(port,
                "GET http://localhost/about.html HTTP/1.1\r\nHost: localhost\r\n\r\n").status, "status"));

        // ------------------------------------------------------------ bodies and framing

        TestRunner.test("POST with lowercase content-length + UTF-8 body keeps stream in sync", () -> {
            // The old server looked up "Content-Length" case-sensitively and counted chars instead of bytes.
            // A lowercase header or a multi-byte character made it read the wrong amount and
            // swallow the start of the next request.
            byte[] body = "{\"city\":\"Zürich\",\"price\":\"₹500\"}".getBytes(UTF_8);
            byte[] head = ("POST /upload HTTP/1.1\r\nhost: x\r\ncontent-type: application/json\r\ncontent-length: "
                + body.length + "\r\n\r\n").getBytes(ISO_8859_1);
            byte[] next = "GET /sample.txt HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(ISO_8859_1);
            try (RawClient client = new RawClient(port)) {
                client.send(ParserTests.concat(head, body, next));
                RawClient.Response post = client.readResponse();
                RawClient.Response get = client.readResponse();
                TestRunner.assertEquals(201, post.status, "POST status");
                TestRunner.assertEquals(200, get.status, "pipelined GET status");
                byte[] saved = Files.readAllBytes(uploadDir.resolve(fileFrom(post)));
                TestRunner.assertTrue(java.util.Arrays.equals(body, saved), "saved file has exactly the sent bytes");
            }
        });

        TestRunner.test("pipelined requests on one connection get ordered responses", () -> {
            try (RawClient client = new RawClient(port)) {
                client.send("GET /index.html HTTP/1.1\r\nHost: x\r\n\r\nGET /contact.html HTTP/1.1\r\nHost: x\r\n\r\n");
                RawClient.Response first = client.readResponse();
                RawClient.Response second = client.readResponse();
                TestRunner.assertEquals(200, first.status, "first");
                TestRunner.assertEquals(200, second.status, "second");
                TestRunner.assertTrue(first.text().contains("Multi-threaded HTTP Server"), "first is index.html");
                TestRunner.assertTrue(second.text().contains("Contact"), "second is contact.html");
            }
        });

        TestRunner.test("chunked request body is decoded and stored", () -> {
            String chunked = "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "7;note=ignored\r\n{\"a\":1,\r\n"
                + "7\r\n\"b\":22}\r\n"
                + "0\r\nX-Checksum: ignored\r\n\r\n"
                + "GET /about.html HTTP/1.1\r\nHost: x\r\n\r\n";
            try (RawClient client = new RawClient(port)) {
                client.send(chunked);
                RawClient.Response post = client.readResponse();
                RawClient.Response get = client.readResponse();
                TestRunner.assertEquals(201, post.status, "POST status");
                TestRunner.assertEquals("{\"a\":1,\"b\":22}",
                    Files.readString(uploadDir.resolve(fileFrom(post))), "decoded body on disk");
                TestRunner.assertEquals(200, get.status, "request after chunked body");
            }
        });

        TestRunner.test("invalid Content-Length values return 400", () -> {
            for (String value : new String[] {"-5", "abc", "1.5"}) {
                RawClient.Response r = RawClient.exchange(port,
                    "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: " + value + "\r\n\r\n");
                TestRunner.assertEquals(400, r.status, "Content-Length: " + value);
                TestRunner.assertEquals("close", r.header("Connection"), "connection closed after framing error");
            }
            RawClient.Response conflict = RawClient.exchange(port,
                "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}x");
            TestRunner.assertEquals(400, conflict.status, "conflicting duplicates");
        });

        TestRunner.test("Content-Length + Transfer-Encoding is rejected (smuggling)", () ->
            TestRunner.assertEquals(400, RawClient.exchange(port, "POST /upload HTTP/1.1\r\nHost: x\r\n"
                + "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").status, "status"));

        TestRunner.test("headers over 8 KB return 431", () -> {
            RawClient.Response r = RawClient.exchange(port,
                "GET / HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(9000) + "\r\n\r\n");
            TestRunner.assertEquals(431, r.status, "status");
        });

        TestRunner.test("body over the limit returns 413 without reading it", () -> {
            RawClient.Response r = RawClient.exchange(port, "POST /upload HTTP/1.1\r\nHost: x\r\n"
                + "Content-Type: application/json\r\nContent-Length: 20000000\r\n\r\n");
            TestRunner.assertEquals(413, r.status, "status");
            withServer(c -> c.maxBodyBytes = 100, small -> {
                RawClient.Response chunked = RawClient.exchange(small.getPort(), "POST /upload HTTP/1.1\r\nHost: x\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n80\r\n" + "a".repeat(128) + "\r\n0\r\n\r\n");
                TestRunner.assertEquals(413, chunked.status, "chunked body over limit");
            });
        });

        TestRunner.test("uploads get unique names and are not served from the web root", () -> {
            String request = "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
                + "Content-Length: 2\r\n\r\n{}";
            String first = fileFrom(RawClient.exchange(port, request));
            String second = fileFrom(RawClient.exchange(port, request));
            TestRunner.assertTrue(first.matches("upload_[0-9a-f-]{36}\\.json"), "UUID file name: " + first);
            TestRunner.assertTrue(!first.equals(second), "two uploads, two names");
            TestRunner.assertEquals(404, RawClient.exchange(port,
                "GET /uploads/" + first + " HTTP/1.1\r\nHost: x\r\n\r\n").status, "upload not served");
            TestRunner.assertEquals(415, RawClient.exchange(port, "POST /upload HTTP/1.1\r\nHost: x\r\n"
                + "Content-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}").status, "wrong content type");
            TestRunner.assertEquals(400, RawClient.exchange(port, "POST /upload HTTP/1.1\r\nHost: x\r\n"
                + "Content-Type: application/json\r\nContent-Length: 3\r\n\r\nabc").status, "not a JSON object");
        });

        // ------------------------------------------------------------ connection management

        TestRunner.test("HTTP/1.1 keep-alive reuses one connection", () -> {
            try (RawClient client = new RawClient(port)) {
                for (int i = 0; i < 3; i++) {
                    RawClient.Response r = client.send("GET /about.html HTTP/1.1\r\nHost: x\r\n\r\n").readResponse();
                    TestRunner.assertEquals(200, r.status, "request " + (i + 1));
                    TestRunner.assertEquals("keep-alive", r.header("Connection"), "Connection on request " + (i + 1));
                }
            }
        });

        TestRunner.test("HTTP/1.1 with Connection: close closes after the response", () -> {
            try (RawClient client = new RawClient(port)) {
                RawClient.Response r = client.send("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").readResponse();
                TestRunner.assertEquals("close", r.header("Connection"), "Connection header");
                TestRunner.assertTrue(client.serverClosed(), "server closed the socket");
            }
        });

        TestRunner.test("HTTP/1.0 closes by default", () -> {
            try (RawClient client = new RawClient(port)) {
                RawClient.Response r = client.send("GET / HTTP/1.0\r\n\r\n").readResponse();
                TestRunner.assertEquals(200, r.status, "status");
                TestRunner.assertEquals("close", r.header("Connection"), "Connection header");
                TestRunner.assertTrue(client.serverClosed(), "server closed the socket");
            }
        });

        TestRunner.test("HTTP/1.0 with Connection: keep-alive stays open", () -> {
            try (RawClient client = new RawClient(port)) {
                String request = "GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n";
                RawClient.Response first = client.send(request).readResponse();
                TestRunner.assertEquals("keep-alive", first.header("Connection"), "Connection header");
                TestRunner.assertEquals(200, client.send(request).readResponse().status, "second request");
            }
        });

        TestRunner.test("connection closes after max requests per connection", () ->
            withServer(c -> c.maxRequestsPerConnection = 3, server -> {
                try (RawClient client = new RawClient(server.getPort())) {
                    for (int i = 1; i <= 3; i++) {
                        RawClient.Response r = client.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n").readResponse();
                        TestRunner.assertEquals(i < 3 ? "keep-alive" : "close", r.header("Connection"), "request " + i);
                    }
                    TestRunner.assertTrue(client.serverClosed(), "closed after 3rd request");
                }
            }));

        TestRunner.test("idle keep-alive connection is closed after the idle timeout", () ->
            withServer(c -> c.idleTimeoutMs = 300, server -> {
                try (RawClient client = new RawClient(server.getPort(), 3000)) {
                    client.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n").readResponse();
                    long start = System.nanoTime();
                    TestRunner.assertTrue(client.serverClosed(), "server closed idle connection");
                    long waited = (System.nanoTime() - start) / 1_000_000;
                    TestRunner.assertTrue(waited >= 200 && waited < 1500, "closed after ~300 ms, took " + waited + " ms");
                }
            }));

        TestRunner.test("headers sent too slowly get 408 at the header deadline", () ->
            withServer(c -> c.headerTimeoutMs = 1000, server -> {
                try (RawClient client = new RawClient(server.getPort(), 4000)) {
                    long start = System.nanoTime();
                    client.send("GET / HTTP/1.1\r\n");
                    // One header byte every 200 ms: each read succeeds, so only a deadline can stop this
                    SlowSender sender = new SlowSender(client, "Host: example.com\r\n", 200);
                    RawClient.Response r = client.readResponse();
                    sender.stop();
                    long took = (System.nanoTime() - start) / 1_000_000;
                    TestRunner.assertEquals(408, r.status, "status");
                    TestRunner.assertTrue(took < 2000, "408 after ~1 s, took " + took + " ms");
                }
            }));

        // ------------------------------------------------------------ concurrency and backpressure

        TestRunner.test("queue full: extra connection gets 503 with Retry-After immediately", () ->
            withServer(c -> { c.threads = 1; c.queueCapacity = 1; c.idleTimeoutMs = 5000; }, server -> {
                try (RawClient busy = new RawClient(server.getPort());     // taken by the only worker
                     RawClient queued = new RawClient(server.getPort())) { // waits in the queue
                    Thread.sleep(200);
                    long start = System.nanoTime();
                    try (RawClient extra = new RawClient(server.getPort())) {
                        RawClient.Response r = extra.readResponse(); // 503 is sent without reading a request
                        long took = (System.nanoTime() - start) / 1_000_000;
                        TestRunner.assertEquals(503, r.status, "status");
                        TestRunner.assertEquals("1", r.header("Retry-After"), "Retry-After");
                        TestRunner.assertEquals("close", r.header("Connection"), "Connection");
                        TestRunner.assertTrue(took < 1000, "rejected quickly, took " + took + " ms");
                    }
                    TestRunner.assertEquals(1L, server.getRejectedConnections(), "rejected count");
                }
            }));

        TestRunner.test("slow clients cannot hang a normal client (platform pool)", () ->
            withServer(c -> { c.threads = 2; c.queueCapacity = 2; c.headerTimeoutMs = 1500; }, server -> {
                // 4 slow clients fill both workers and both queue slots, each sending one byte per second
                List<SlowSender> slow = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    RawClient client = new RawClient(server.getPort(), 10_000);
                    client.send("GET / HTTP/1.1\r\n");
                    slow.add(new SlowSender(client, "X-Slow: " + "a".repeat(50) + "\r\n", 1000));
                }
                Thread.sleep(200);
                try {
                    // A normal client must always get an answer quickly: 503 while we are full,
                    // then 200 once the header deadline has kicked the slow clients out.
                    long start = System.nanoTime();
                    int status = 0;
                    List<Integer> seen = new ArrayList<>();
                    while (status != 200 && System.nanoTime() - start < 8_000_000_000L) {
                        long attempt = System.nanoTime();
                        try (RawClient normal = new RawClient(server.getPort(), 3000)) {
                            status = normal.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n").readResponse().status;
                        } catch (SocketTimeoutException e) {
                            throw new AssertionError("normal client hung for more than 3 s");
                        }
                        long took = (System.nanoTime() - attempt) / 1_000_000;
                        TestRunner.assertTrue(status == 200 || status == 503, "unexpected status " + status);
                        TestRunner.assertTrue(took < 2500, "answer took " + took + " ms");
                        seen.add(status);
                        if (status != 200) Thread.sleep(250);
                    }
                    TestRunner.assertEquals(503, seen.get(0), "first attempt is rejected while full");
                    TestRunner.assertEquals(200, status, "eventually served, attempts: " + seen);
                    for (SlowSender s : slow) {
                        TestRunner.assertEquals(408, s.client.readResponse().status, "slow client got 408");
                    }
                } finally {
                    slow.forEach(SlowSender::stop);
                }
            }));

        TestRunner.test("virtual mode serves requests and is not blocked by slow clients", () ->
            withServer(c -> { c.mode = "virtual"; c.headerTimeoutMs = 1500; }, server -> {
                List<SlowSender> slow = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    RawClient client = new RawClient(server.getPort(), 10_000);
                    client.send("GET / HTTP/1.1\r\n");
                    slow.add(new SlowSender(client, "X-Slow: aaaa\r\n", 1000));
                }
                try (RawClient normal = new RawClient(server.getPort(), 2000)) {
                    long start = System.nanoTime();
                    for (int i = 0; i < 3; i++) {
                        TestRunner.assertEquals(200,
                            normal.send("GET /about.html HTTP/1.1\r\nHost: x\r\n\r\n").readResponse().status, "status");
                    }
                    long took = (System.nanoTime() - start) / 1_000_000;
                    TestRunner.assertTrue(took < 1000, "served while 20 slow clients connected, took " + took + " ms");
                } finally {
                    slow.forEach(SlowSender::stop);
                }
            }));
    }

    // ------------------------------------------------------------------ helpers

    /** Writes one byte of text at a fixed interval from a background thread, like a Slowloris client. */
    static class SlowSender {
        final RawClient client;
        private final Thread thread;

        SlowSender(RawClient client, String text, long intervalMs) {
            this.client = client;
            this.thread = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < text.length(); i++) {
                        Thread.sleep(intervalMs);
                        client.send(text.substring(i, i + 1));
                    }
                } catch (InterruptedException | IOException e) {
                    // stopped, or the server closed the connection
                }
            });
        }

        void stop() {
            thread.interrupt();
            client.close();
        }
    }

    interface ServerBody {
        void run(Server server) throws Exception;
    }

    private static Server startServer(Consumer<ServerConfig> customize) throws IOException {
        ServerConfig config = new ServerConfig();
        config.port = 0; // any free port
        config.webRoot = WEB_ROOT;
        config.uploadDir = uploadDir;
        config.idleTimeoutMs = 2000;
        config.headerTimeoutMs = 2000;
        config.bodyReadTimeoutMs = 2000;
        config.lingerMs = 200;
        config.statsIntervalSeconds = 0;
        config.quiet = true;
        customize.accept(config);
        Server server = new Server(config);
        server.start();
        return server;
    }

    /** Starts a server with special settings just for one test and always stops it. */
    private static void withServer(Consumer<ServerConfig> customize, ServerBody body) throws Exception {
        Server server = startServer(customize);
        try {
            body.run(server);
        } finally {
            server.stop();
        }
    }

    private static String fileFrom(RawClient.Response uploadResponse) {
        Matcher m = Pattern.compile("\"file\":\"([^\"]+)\"").matcher(uploadResponse.text());
        if (!m.find()) throw new AssertionError("no file name in " + uploadResponse.text());
        return m.group(1);
    }

    static void shutdown() throws IOException {
        if (shared != null) shared.stop();
        if (uploadDir != null) {
            try (Stream<Path> files = Files.walk(uploadDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
