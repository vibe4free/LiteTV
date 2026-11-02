package com.cabletv.player.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The request parser's limits.
 *
 * <p>Everything here arrives from the network, so each bound is checked on its own: a header line
 * that never ends, a body larger than the cap, a Content-Length that is not a number, a body that
 * stops early. "The server stayed up" is not enough -- the answer has to be the right status.
 */
public class HttpRequestTest {

    /** A socket stand-in: the parser only needs its streams and a timeout it can set. */
    private static Socket socketOf(byte[] request) {
        final InputStream in = new ByteArrayInputStream(request);
        final OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                // Discard.
            }
        };
        return new Socket() {
            @Override
            public InputStream getInputStream() {
                return in;
            }

            @Override
            public OutputStream getOutputStream() {
                return sink;
            }

            @Override
            public synchronized void setSoTimeout(int timeout) {
                // Nothing to configure for a byte array.
            }
        };
    }

    private static HttpRequest parse(String raw) throws Exception {
        return HttpRequest.read(socketOf(raw.getBytes(StandardCharsets.UTF_8)), "/config/m3u-file-upload");
    }

    @Test
    public void aPlainGetIsParsed() throws Exception {
        HttpRequest request = parse("GET /config/get HTTP/1.1\r\nHost: 127.0.0.1:8899\r\n\r\n");

        assertTrue(request.isGet());
        assertEquals("/config/get", request.path);
        assertEquals("127.0.0.1:8899", request.host);
        assertNull("a GET has no body", request.body);
        assertEquals("", request.bodyText());
    }

    @Test
    public void aFormPostCarriesItsTextBody() throws Exception {
        String body = "merge_same_name_channels=0";
        HttpRequest request = parse("POST /config/channels HTTP/1.1\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body);

        assertTrue(request.isPost());
        assertEquals(body, request.bodyText());
    }

    /** A multipart boundary is case-sensitive, so the header value must survive untouched. */
    @Test
    public void theContentTypeKeepsItsCase() throws Exception {
        HttpRequest request = parse("POST /config/m3u-file-upload HTTP/1.1\r\n"
                + "Content-Type: multipart/form-data; boundary=----WebKitFormBoundaryAbC123\r\n"
                + "Content-Length: 3\r\n\r\nabc");

        assertTrue(request.contentType.contains("----WebKitFormBoundaryAbC123"));
    }

    @Test
    public void aBrokenRequestLineIsRejected() {
        try {
            parse("GET\r\n\r\n");
            fail("a request line without a path must be refused");
        } catch (HttpRequest.HttpError e) {
            assertEquals(400, e.status);
        } catch (IOException e) {
            fail("expected a 400, not a transport error");
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    @Test
    public void aContentLengthThatIsNotANumberIsRejected() {
        try {
            parse("POST /config/channels HTTP/1.1\r\nContent-Length: banana\r\n\r\n");
            fail("must refuse a bogus Content-Length");
        } catch (HttpRequest.HttpError e) {
            assertEquals(400, e.status);
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    @Test
    public void aNegativeContentLengthIsRejected() {
        try {
            parse("POST /config/channels HTTP/1.1\r\nContent-Length: -5\r\n\r\n");
            fail("must refuse a negative Content-Length");
        } catch (HttpRequest.HttpError e) {
            assertEquals(400, e.status);
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    /** A small request may not smuggle in a body sized for a playlist upload. */
    @Test
    public void anOrdinaryRequestCannotCarryAnUploadSizedBody() {
        try {
            parse("POST /config/channels HTTP/1.1\r\nContent-Length: "
                    + (HttpRequest.MAX_BODY_BYTES + 1) + "\r\n\r\n");
            fail("an oversized form body must be refused");
        } catch (HttpRequest.HttpError e) {
            assertEquals(413, e.status);
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    /** The upload path is the one place a large body is allowed. */
    @Test
    public void theUploadPathMayCarryALargeBody() throws Exception {
        int size = HttpRequest.MAX_BODY_BYTES + 10;
        StringBuilder request = new StringBuilder("POST /config/m3u-file-upload HTTP/1.1\r\n"
                + "Content-Length: " + size + "\r\n\r\n");
        for (int i = 0; i < size; i++) {
            request.append('x');
        }

        HttpRequest parsed = parse(request.toString());

        assertEquals(size, parsed.body.length);
    }

    /** A body that stops early is a broken request, not something to hand to the settings code. */
    @Test
    public void aTruncatedBodyIsRejected() {
        try {
            parse("POST /config/channels HTTP/1.1\r\nContent-Length: 100\r\n\r\nshort");
            fail("a truncated body must be refused");
        } catch (HttpRequest.HttpError e) {
            assertEquals(400, e.status);
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    /** An endless header line must not grow the buffer without bound. */
    @Test
    public void anEndlessHeaderLineIsCut() {
        StringBuilder request = new StringBuilder("GET / HTTP/1.1\r\nX-Long: ");
        for (int i = 0; i < 5000; i++) {
            request.append('a');
        }
        try {
            parse(request.toString());
            fail("an over-long header line must be refused");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("exceeds"));
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    @Test
    public void theUploadLimitIsMuchLargerThanTheOrdinaryOne() {
        assertTrue(HttpRequest.bodyLimitFor("/config/m3u-file-upload", "/config/m3u-file-upload")
                > HttpRequest.bodyLimitFor("/config/channels", "/config/m3u-file-upload"));
    }

    @Test
    public void theBodyIsKeptAsBytesForUploads() throws Exception {
        byte[] raw = {0x00, 0x01, (byte) 0xFF, 0x7F};
        java.io.ByteArrayOutputStream request = new java.io.ByteArrayOutputStream();
        request.write(("POST /config/m3u-file-upload HTTP/1.1\r\nContent-Length: " + raw.length
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        request.write(raw);

        HttpRequest parsed = HttpRequest.read(
                socketOf(request.toByteArray()), "/config/m3u-file-upload");

        assertArrayEquals("multipart payloads are not always valid UTF-8", raw, parsed.body);
    }
}
