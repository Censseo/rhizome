package rhizome.adversarial.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP client that writes the bytes itself, for scenarios whose subject is the request a
 * well-behaved client would refuse to make.
 *
 * <p>{@code java.net.http.HttpClient} silently drops {@code Host}, {@code Connection} and other
 * restricted headers unless the JVM is started with a system property. That makes it useless here:
 * the node's two browser guards are a {@code Host} allowlist (anti-DNS-rebinding) and an
 * {@code Origin}/marker pair (anti-CSRF), and an attacker forging those headers is precisely the
 * threat. A test that could not set {@code Host} would appear to prove the allowlist works while
 * never having sent a request that challenges it.
 *
 * <p>So the request goes out on a raw socket, exactly as an attacker's would. The same primitive
 * covers the slow-loris shape, where the point is to send a request and then <em>not</em> finish
 * it.
 */
final class RawHttp {

    private RawHttp() {
    }

    /** Status code, headers and body of one exchange. */
    record Response(int status, Map<String, String> headers, String body) {
    }

    /** Builds a request with no body. */
    static Response get(int port, String path, Map<String, String> headers) {
        return send(port, "GET", path, headers, new byte[0]);
    }

    static Response post(int port, String path, Map<String, String> headers, byte[] body) {
        return send(port, "POST", path, headers, body);
    }

    /**
     * Sends one complete request and reads the response. {@code Host} defaults to the loopback
     * authority the node is listening on, and any caller-supplied {@code Host} replaces it — which
     * is the whole point for the rebinding scenario.
     */
    static Response send(int port, String method, String path, Map<String, String> headers, byte[] body) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Host", "127.0.0.1:" + port);
        all.putAll(headers);
        all.put("Content-Length", String.valueOf(body.length));
        all.put("Connection", "close");

        StringBuilder head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        all.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return read(socket.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("raw " + method + " " + path + " failed", e);
        }
    }

    /**
     * Opens a connection, announces a body and never sends it — the slow-loris shape. Returns the
     * socket so the scenario can hold it open, and closes nothing: the caller owns it.
     */
    static Socket startAndStall(int port, String path, int announcedBodyBytes) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(30_000);
            String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Content-Length: " + announcedBodyBytes + "\r\n"
                + "\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return socket;
        } catch (IOException e) {
            throw new UncheckedIOException("could not open a stalled exchange", e);
        }
    }

    private static Response read(InputStream in) throws IOException {
        byte[] raw = in.readAllBytes();
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int headEnd = text.indexOf("\r\n\r\n");
        String head = headEnd < 0 ? text : text.substring(0, headEnd);
        String body = headEnd < 0 ? "" : text.substring(headEnd + 4);

        String[] lines = head.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("not an HTTP response: " + head);
        }
        String[] statusParts = lines[0].split(" ");
        int status = Integer.parseInt(statusParts[1]);

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
            }
        }
        return new Response(status, headers, body);
    }
}
