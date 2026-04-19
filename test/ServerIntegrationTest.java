import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ServerIntegrationTest {

    private static final String BASE_URL = "http://127.0.0.1:8081";
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) {
        System.out.println("Starting Integration Tests...");
        
        // 1. Start Server on a Background Daemon Thread (Port 8081)
        Thread serverThread = new Thread(() -> {
            Server.main(new String[]{"8081", "127.0.0.1", "10"});
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            // Give the ServerSocket a moment to bind
            Thread.sleep(500);

            int passed = 0;
            int total = 5;

            // Test 1: Happy Path GET (200 OK)
            passed += runTest("GET /", () -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/"))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertStatus(response, 200);
            });

            // Test 2: Missing Resource (404 Not Found)
            passed += runTest("GET /missing.txt", () -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/missing.txt"))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertStatus(response, 404);
            });

            // Test 3: Unsupported Method (405 Method Not Allowed)
            passed += runTest("PUT /", () -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/"))
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertStatus(response, 405);
            });

            // Test 4: POST with bad data (400 Bad Request)
            passed += runTest("POST /upload (Bad Data)", () -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/upload"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("INVALID JSON"))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertStatus(response, 400);
            });

            // Test 5: POST with good JSON (201 Created)
            passed += runTest("POST /upload (Success)", () -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/upload"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"value\"}"))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertStatus(response, 201);
            });

            System.out.println("==================================================");
            if (passed == total) {
                System.out.printf("[RESULTS] All %d tests passed successfully! ✓%n", passed);
            } else {
                System.err.printf("[RESULTS] %d/%d tests passed. ❌%n", passed, total);
                System.exit(1);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Forcefully shut down the server loop to exit gracefully
            Server.stopServer();
        }
    }

    private static int runTest(String name, TestRunnable tester) {
        try {
            System.out.printf("Running Test: %-30s", name);
            tester.run();
            System.out.println(" [PASS]");
            return 1;
        } catch (AssertionError | Exception e) {
            System.out.println(" [FAIL] -> " + e.getMessage());
            return 0;
        }
    }

    private static void assertStatus(HttpResponse<?> response, int expected) {
        if (response.statusCode() != expected) {
            throw new AssertionError("Expected " + expected + ", got " + response.statusCode());
        }
    }

    @FunctionalInterface
    interface TestRunnable {
        void run() throws Exception;
    }
}
