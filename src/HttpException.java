/**
 * Thrown when a request is invalid. Carries the status code that should be
 * sent back, e.g. 400 for a malformed header or 413 for a body that is too big.
 * After one of these the connection is always closed, because we can no longer
 * be sure where the next request starts.
 */
public class HttpException extends Exception {
    private final int statusCode;

    public HttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
