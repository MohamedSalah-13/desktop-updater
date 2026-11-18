package com.hamza.updater.internal;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library's requests, over the JDK's {@link HttpURLConnection}: it is in {@code java.base}, follows a
 * redirect to the file's storage, and gives up a connection that stops sending.
 *
 * <p>HTTPS only. Plain HTTP is accepted for this computer alone, which is how a release is tried out
 * before it is published.
 */
public final class Http {

    private static final int CONNECT_TIMEOUT = 15_000;
    /** How long a connection may send nothing before it is given up. */
    private static final int READ_TIMEOUT = 60_000;
    /** No answer the library reads as text is anywhere near this. */
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private static final String AGENT = "desktop-updater";
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-\\d+/(?:\\d+|\\*)");

    private Http() {
    }

    /** @param body the answer as text; empty for an error status */
    public record Text(int status, String body) {
    }

    /**
     * @param rangeStart the first byte of a 206 answer; -1 when the server did not say
     * @param length     the bytes of the body; -1 when the server did not say
     */
    public record Body(int status, long rangeStart, long length, InputStream stream) {
    }

    public static Text get(URI uri, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = open(uri, headers);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return new Text(status, "");
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    bytes.write(buffer, 0, read);
                    if (bytes.size() > MAX_TEXT_BYTES) {
                        throw new IOException("The answer is larger than " + MAX_TEXT_BYTES + " bytes");
                    }
                }
                return new Text(status, bytes.toString(StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Opens a file from a byte on; closing the stream closes the connection. */
    public static Body download(URI uri, long from) throws IOException {
        HttpURLConnection connection = open(uri, Map.of("Accept", "application/octet-stream"));
        try {
            if (from > 0) {
                connection.setRequestProperty("Range", "bytes=" + from + "-");
            }
            int status = connection.getResponseCode();
            long rangeStart = -1;
            String range = connection.getHeaderField("Content-Range");
            if (range != null) {
                Matcher matcher = RANGE.matcher(range.strip());
                if (matcher.matches()) {
                    rangeStart = Long.parseLong(matcher.group(1));
                }
            }
            InputStream stream = status >= 400 ? InputStream.nullInputStream() : connection.getInputStream();
            return new Body(status, rangeStart, connection.getContentLengthLong(), new FilterInputStream(stream) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        connection.disconnect();
                    }
                }
            });
        } catch (IOException | RuntimeException failure) {
            connection.disconnect();
            throw failure;
        }
    }

    private static HttpURLConnection open(URI uri, Map<String, String> headers) throws IOException {
        requireSafe(uri);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", AGENT);
        headers.forEach(connection::setRequestProperty);
        return connection;
    }

    /** @throws IOException when the address is neither HTTPS nor this computer */
    public static void requireSafe(URI uri) throws IOException {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw new IOException("Not an address: " + uri);
        }
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && local)) {
            throw new IOException("Only HTTPS addresses are used: " + uri);
        }
    }
}
