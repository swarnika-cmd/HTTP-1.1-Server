import java.util.ArrayList;
import java.util.List;

/**
 * A very small test runner, so the project needs nothing but the JDK.
 * Each test is a named lambda; an exception (including AssertionError) means FAIL.
 *
 * Usage: java -cp out TestRunner [name-filter]
 */
public class TestRunner {

    @FunctionalInterface
    interface TestBody {
        void run() throws Exception;
    }

    private record TestCase(String name, TestBody body) {}

    private static final List<TestCase> TESTS = new ArrayList<>();

    static void test(String name, TestBody body) {
        TESTS.add(new TestCase(name, body));
    }

    public static void main(String[] args) throws Exception {
        String filter = args.length > 0 ? args[0] : "";

        ParserTests.register();
        ServerTests.register();

        int passed = 0;
        List<String> failures = new ArrayList<>();
        long suiteStart = System.nanoTime();

        try {
            for (TestCase test : TESTS) {
                if (!test.name().contains(filter)) continue;
                long start = System.nanoTime();
                try {
                    test.body().run();
                    passed++;
                    System.out.printf("PASS  %-70s %5d ms%n", test.name(), elapsedMs(start));
                } catch (Throwable t) {
                    failures.add(test.name());
                    System.out.printf("FAIL  %-70s %5d ms%n      -> %s%n", test.name(), elapsedMs(start), t);
                }
            }
        } finally {
            ServerTests.shutdown();
        }

        System.out.println("------------------------------------------------------------------------------");
        System.out.printf("%d passed, %d failed, %d total (%.1f s)%n",
            passed, failures.size(), passed + failures.size(), elapsedMs(suiteStart) / 1000.0);
        for (String name : failures) {
            System.out.println("  failed: " + name);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------ assertions

    static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertThrowsStatus(int expectedStatus, TestBody body) throws Exception {
        try {
            body.run();
        } catch (HttpException e) {
            assertEquals(expectedStatus, e.getStatusCode(), "status of HttpException (" + e.getMessage() + ")");
            return;
        }
        throw new AssertionError("expected HttpException with status " + expectedStatus);
    }
}
