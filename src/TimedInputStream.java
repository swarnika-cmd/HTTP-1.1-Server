import java.io.FilterInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Wraps a socket's input stream and sets the socket read timeout before every
 * read from the network. It has two modes:
 *
 *  - read timeout: each read may wait at most N ms (plain SO_TIMEOUT)
 *  - deadline: all reads together must be done before a fixed moment
 *
 * A read timeout alone does not stop a Slowloris client: sending one byte every
 * few seconds resets it forever. A deadline cannot be pushed back that way.
 *
 * This sits under a BufferedInputStream, so it is only called when the buffer
 * is empty and we really have to wait for the network.
 */
public class TimedInputStream extends FilterInputStream {
    private final Socket socket;
    private int readTimeoutMs;
    private long deadlineNanos; // 0 means "no deadline"

    public TimedInputStream(Socket socket) throws IOException {
        super(socket.getInputStream());
        this.socket = socket;
    }

    /** Each read may block for at most this long. Clears any deadline. */
    public void setReadTimeout(int millis) {
        readTimeoutMs = millis;
        deadlineNanos = 0;
    }

    /** All reads from now on must finish within this many milliseconds in total. */
    public void startDeadline(int millis) {
        deadlineNanos = System.nanoTime() + millis * 1_000_000L;
    }

    @Override
    public int read() throws IOException {
        applyTimeout();
        return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        applyTimeout();
        return super.read(b, off, len);
    }

    private void applyTimeout() throws IOException {
        int timeout = readTimeoutMs;
        if (deadlineNanos != 0) {
            long millisLeft = (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (millisLeft <= 0) {
                throw new SocketTimeoutException("Deadline passed");
            }
            timeout = (int) Math.min(millisLeft, Integer.MAX_VALUE);
        }
        socket.setSoTimeout(timeout);
    }
}
