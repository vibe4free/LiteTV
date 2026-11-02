package com.cabletv.player.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One HTTP request, read off a socket.
 *
 * <p>Parsing lives here rather than in the server so the limits can be exercised on their own:
 * every read is bounded, every malformed input has a definite answer, and the server is left with
 * the part that is actually about settings (routing and policy).
 *
 * <p>Kept deliberately small: this is a configuration page on a home network, not a general
 * purpose server. There is no keep-alive, no chunked encoding and no pipelining -- one request
 * per connection, which is what a browser talking to a settings page does anyway.
 */
final class HttpRequest {

    /** Rejected before it ever reaches the settings code. */
    static final class HttpError extends Exception {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final int MAX_REQUEST_LINE_BYTES = 2048;
    private static final int MAX_HEADER_LINES = 64;
    /** Everything except a playlist upload stays tiny. */
    static final int MAX_BODY_BYTES = 64 * 1024;
    /** A playlist may be a few MB; the extra room covers multipart framing. */
    static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024 + 64 * 1024;

    private static final int HEAD_TIMEOUT_MS = 3_000;
    private static final int BODY_TIMEOUT_MS = 10_000;
    private static final int UPLOAD_TIMEOUT_MS = 60_000;

    final String method;
    final String path;
    /** Browser origin, or null when the request did not carry one. */
    final String origin;
    final String host;
    /** As sent, case preserved: a multipart boundary is case-sensitive. */
    final String contentType;
    /** Raw bytes; null when the request had no body. */
    final byte[] body;

    private HttpRequest(String method, String path, String origin, String host,
            String contentType, byte[] body) {
        this.method = method;
        this.path = path;
        this.origin = origin;
        this.host = host;
        this.contentType = contentType;
        this.body = body;
    }

    /** The body decoded as UTF-8 ("" when there is none). */
    String bodyText() {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    boolean isPost() {
        return "POST".equals(method);
    }

    boolean isGet() {
        return "GET".equals(method);
    }

    /**
     * Reads one request.
     *
     * @param uploadPath the path allowed to carry a large body; anything else is capped small
     * @throws HttpError when the request is malformed or too large (carries the status to send)
     */
    static HttpRequest read(Socket socket, String uploadPath) throws IOException, HttpError {
        socket.setSoTimeout(HEAD_TIMEOUT_MS);
        InputStream in = socket.getInputStream();

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("Empty request line");
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new HttpError(400, "Malformed request line");
        }
        String method = parts[0];
        String path = parts[1];

        int contentLength = 0;
        String origin = null;
        String host = null;
        String contentType = "";
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.startsWith("content-length:")) {
                contentLength = parseContentLength(line.substring("content-length:".length()));
                if (contentLength < 0) {
                    throw new HttpError(400, "Bad Content-Length");
                }
                if (contentLength > bodyLimitFor(path, uploadPath)) {
                    throw new HttpError(413, "Payload too large");
                }
            } else if (lower.startsWith("origin:")) {
                origin = line.substring("origin:".length()).trim();
            } else if (lower.startsWith("host:")) {
                host = line.substring("host:".length()).trim();
            } else if (lower.startsWith("content-type:")) {
                // Keep the original case: a multipart boundary is case-sensitive even though the
                // header name and its parameters are not.
                contentType = line.substring("content-type:".length()).trim();
            }
        }

        byte[] body = null;
        if (contentLength > 0) {
            socket.setSoTimeout(uploadPath.equals(path) ? UPLOAD_TIMEOUT_MS : BODY_TIMEOUT_MS);
            body = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = in.read(body, offset, contentLength - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset < contentLength) {
                throw new HttpError(400, "Truncated body");
            }
        }
        return new HttpRequest(method, path, origin, host, contentType, body);
    }

    /** Playlist uploads may be several MB; every other request stays tiny. */
    static int bodyLimitFor(String path, String uploadPath) {
        return uploadPath.equals(path) ? MAX_UPLOAD_BYTES : MAX_BODY_BYTES;
    }

    private static int parseContentLength(String value) {
        try {
            long length = Long.parseLong(value.trim());
            if (length < 0) {
                return -1;
            }
            // Clamp instead of overflowing; the caller rejects anything above the cap.
            return length > MAX_UPLOAD_BYTES ? MAX_UPLOAD_BYTES + 1 : (int) length;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Reads one CRLF-terminated line, refusing to grow past the header limit. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            if (b == '\r') {
                continue;
            }
            if (buffer.size() >= MAX_REQUEST_LINE_BYTES) {
                throw new IOException("Header line exceeds " + MAX_REQUEST_LINE_BYTES + " bytes");
            }
            buffer.write(b);
        }
        return buffer.size() == 0 ? null : new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Headers as a map, lower-cased keys (used by tests and multipart parsing). */
    Map<String, String> headerView() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null && !contentType.isEmpty()) {
            headers.put("content-type", contentType);
        }
        if (origin != null) {
            headers.put("origin", origin);
        }
        if (host != null) {
            headers.put("host", host);
        }
        return Collections.unmodifiableMap(headers);
    }
}
