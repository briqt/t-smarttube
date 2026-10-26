package com.liskovsoft.smartyoutubetv2.common.subtitle.pairing;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Short-lived HTTP transport on one explicitly selected local address. This is pairing authorization,
 * not transport encryption. Backend owns JSON semantics, lifecycle scope and actual persistence.
 * No Android dependencies, address discovery, request logging or credential-bearing diagnostics.
 */
public final class CaptionPairingServer implements Closeable {
    /** Monotonic elapsed time, including suspend. Must be thread-safe and nonblocking. */
    public interface Clock { long nowMs(); }
    /** Must be thread-safe and nonblocking. The transport does not interpret playback generations. */
    public interface Gate { boolean current(); }
    public interface Completion { void complete(Reply reply); }
    /**
     * Invoked on the network worker. Return promptly; completion may arrive on any thread.
     * The save byte array belongs to the backend after dispatch and must not be logged or retained
     * beyond processing. Only report saved=true after the production persistence acknowledgement.
     */
    public interface Backend {
        void read(Completion completion);
        void save(byte[] utf8, Completion completion);
        /** Optional explicitly requested probe; never reports saved or consumes pairing. */
        default void test(byte[] utf8, Completion completion) {
            completion.complete(new Reply(501, "{\"error\":\"unsupported\"}", false));
        }
    }
    /** Called once by the shutdown initiator; must return promptly and marshal UI work itself. */
    public interface Listener { void closed(boolean saved); }
    public static final class Reply {
        public final int status;
        public final String json;
        public final boolean saved;
        public Reply(int status, String json, boolean saved) {
            this.status = status;
            this.json = json;
            this.saved = saved;
        }
    }
    public static final class Asset {
        public final String contentType;
        public final byte[] bytes;
        public Asset(String contentType, byte[] bytes) {
            this.contentType = contentType;
            this.bytes = bytes == null ? null : bytes.clone();
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int MAX_HEADERS = 8192;
    private static final int MAX_LINE = 2048;
    private static final int MAX_FIELDS = 32;
    private static final int MAX_BODY = 32768;
    private static final int MAX_RESPONSE = 65536;
    private static final int MAX_RESPONSE_BODY = MAX_RESPONSE - 2048;
    private static final long TTL_MS = 5 * 60 * 1000;
    private static final long READ_MS = 8000;
    private static final int IDLE_MS = 3000;
    private static final long BACKEND_MS = 10000;
    private static final long WRITE_MS = 2000;
    private static final int READING = 1;
    private static final int WAITING = 2;
    private static final int WRITING = 3;
    private static final String ERROR = "{\"error\":\"request_rejected\"}";
    private static final String UNKNOWN = "{\"error\":\"result_unknown\"}";

    private final Object mLock = new Object();
    private final Clock mClock;
    private final Gate mGate;
    private final Backend mBackend;
    private final Listener mListener;
    private final Map<String, Asset> mAssets;
    private final ServerSocket mServer;
    private final String mAuthority;
    private final String mOrigin;
    private final long mExpires;
    private final Thread mNetwork;
    private final Thread mWatchdog;
    private byte[] mAuthorization;
    private String mToken;
    private volatile boolean mClosed;
    private boolean mSaved;
    private boolean mConsumed;
    private boolean mCancelled;
    private Socket mActive;
    private int mPhase;
    private long mSocketDeadline;
    private Ticket mWaiting;
    private Ticket mInFlight;

    public static CaptionPairingServer start(InetAddress bind, Map<String, Asset> assets,
            Clock clock, Gate gate, Backend backend, Listener listener) throws IOException {
        return new CaptionPairingServer(bind, assets, clock, gate, backend, listener, TTL_MS, false);
    }

    /** Test-only entry: loopback is never accepted by the public production entry. */
    static CaptionPairingServer start(InetAddress bind, Map<String, Asset> assets,
            Clock clock, Gate gate, Backend backend, Listener listener, long ttlMs) throws IOException {
        return new CaptionPairingServer(bind, assets, clock, gate, backend, listener, ttlMs, true);
    }

    private CaptionPairingServer(InetAddress bind, Map<String, Asset> assets, Clock clock,
            Gate gate, Backend backend, Listener listener, long ttlMs, boolean test) throws IOException {
        if (!localAddress(bind, test) || clock == null || gate == null || backend == null ||
                listener == null || ttlMs <= 0 || ttlMs > TTL_MS) throw invalid();
        mClock = clock;
        mGate = gate;
        mBackend = backend;
        mListener = listener;
        mAssets = copyAssets(assets);
        mExpires = deadline(clock.nowMs(), ttlMs);
        if (!gateCurrent()) throw new IOException("Pairing unavailable");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = new char[64];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < random.length; i++) {
            hex[i * 2] = digits[(random[i] & 255) >>> 4];
            hex[i * 2 + 1] = digits[random[i] & 15];
        }
        Arrays.fill(random, (byte) 0);
        mToken = new String(hex);
        Arrays.fill(hex, '\0');
        mAuthorization = ("Pair " + mToken).getBytes(ASCII);
        mServer = new ServerSocket();
        try {
            mServer.bind(new InetSocketAddress(bind, 0), 1);
            mServer.setSoTimeout(200);
        } catch (IOException failure) {
            closeQuietly(mServer);
            Arrays.fill(mAuthorization, (byte) 0);
            mToken = null;
            throw new IOException("Pairing listener unavailable");
        }
        mAuthority = numericHost(bind) + ":" + mServer.getLocalPort();
        mOrigin = "http://" + mAuthority;
        mNetwork = new Thread(this::runNetwork, "caption-pairing-net");
        mWatchdog = new Thread(this::runWatchdog, "caption-pairing-deadline");
        mNetwork.setDaemon(true);
        mWatchdog.setDaemon(true);
        mNetwork.start();
        mWatchdog.start();
    }

    public String origin() { return mOrigin; }
    /** Empty after shutdown; callers must never log or persist this URL. */
    public String pairingUrl() {
        synchronized (mLock) { return mClosed || mConsumed ? "" : mOrigin + "/#" + mToken; }
    }
    public boolean isClosed() { return mClosed; }

    @Override public void close() {
        Socket active;
        boolean saved;
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true;
            saved = mSaved;
            active = mActive;
            mToken = null;
            Arrays.fill(mAuthorization, (byte) 0);
            if (mWaiting != null) mWaiting.cancelled = true;
            if (mInFlight != null) mInFlight.cancelled = true;
            mWaiting = mInFlight = null;
            mLock.notifyAll();
        }
        closeQuietly(mServer);
        closeQuietly(active);
        mNetwork.interrupt();
        mWatchdog.interrupt();
        try { mListener.closed(saved); } catch (RuntimeException ignored) { /* No caller data in logs. */ }
    }

    /** Waits for both owned threads, not merely for the logical closed flag. */
    public boolean awaitClosed(long ms) throws InterruptedException {
        if (ms < 0) throw invalid();
        long start = System.nanoTime();
        long budget = ms > Long.MAX_VALUE / 1000000 ? Long.MAX_VALUE : ms * 1000000;
        return join(mNetwork, start, budget) && join(mWatchdog, start, budget);
    }

    private static boolean join(Thread thread, long start, long budget) throws InterruptedException {
        if (thread == Thread.currentThread()) return !thread.isAlive();
        while (thread.isAlive()) {
            long remaining = budget - (System.nanoTime() - start);
            if (remaining <= 0) return false;
            thread.join(remaining / 1000000, (int) (remaining % 1000000));
        }
        return true;
    }

    private void runNetwork() {
        try {
            while (live()) {
                Socket socket;
                try { socket = mServer.accept(); }
                catch (SocketTimeoutException idle) { continue; }
                catch (IOException stopped) { break; }
                synchronized (mLock) {
                    if (mClosed || mConsumed) { closeQuietly(socket); break; }
                    mActive = socket;
                    mPhase = READING;
                    mSocketDeadline = deadline(mClock.nowMs(), READ_MS);
                }
                try {
                    socket.setSoTimeout(IDLE_MS);
                    socket.setSendBufferSize(8192);
                    serve(socket);
                } catch (HttpFailure rejected) {
                    if (live()) try { send(socket, rejected.status, "application/json; charset=utf-8", ERROR.getBytes(UTF8)); }
                    catch (IOException ignored) { }
                } catch (IOException | RuntimeException ignored) {
                    // Deliberately no throwable, request, URL or backend output is logged.
                } finally {
                    closeQuietly(socket);
                    synchronized (mLock) { if (mActive == socket) mActive = null; }
                }
                synchronized (mLock) { if (mConsumed) break; }
            }
        } finally { close(); }
    }

    private void runWatchdog() {
        try {
            while (!mClosed) {
                if (!live()) break;
                Socket expired = null;
                synchronized (mLock) {
                    if (mActive != null && mPhase != WAITING && mClock.nowMs() >= mSocketDeadline) expired = mActive;
                }
                closeQuietly(expired); // Includes writes: SO_TIMEOUT alone only bounds reads.
                Thread.sleep(20);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            close();
        }
    }

    private boolean live() {
        if (mClosed) return false;
        boolean valid;
        try { valid = mClock.nowMs() < mExpires && gateCurrent(); }
        catch (RuntimeException ignored) { valid = false; }
        if (!valid) close();
        return valid && !mClosed;
    }
    private boolean gateCurrent() {
        try { return mGate.current(); } catch (RuntimeException ignored) { return false; }
    }

    private void serve(Socket socket) throws IOException, HttpFailure {
        Request request = readRequest(socket);
        if (!live()) return;
        if (!mAuthority.equals(request.headers.get("host"))) throw new HttpFailure(403);
        if (!sameOrigin(request)) throw new HttpFailure(403);
        if (request.headers.containsKey("transfer-encoding")) throw new HttpFailure(400);
        if (request.headers.containsKey("content-encoding")) throw new HttpFailure(415);
        if (request.headers.containsKey("expect")) throw new HttpFailure(417);
        int length = contentLength(request.headers.get("content-length"), "POST".equals(request.method));
        if ("GET".equals(request.method)) {
            if (length != 0) throw new HttpFailure(400);
            Asset asset = mAssets.get(request.path);
            if (asset == null) throw new HttpFailure(404);
            send(socket, 200, asset.contentType, asset.bytes);
            return;
        }
        if (!"POST".equals(request.method)) throw new HttpFailure(405);
        if (!"/api/state".equals(request.path) && !"/api/save".equals(request.path) &&
                !"/api/test".equals(request.path) && !"/api/cancel".equals(request.path)) throw new HttpFailure(404);
        byte[] authorization = request.headers.get("authorization") == null ? new byte[0] :
                request.headers.get("authorization").getBytes(ASCII);
        boolean authorized;
        synchronized (mLock) { authorized = !mClosed && MessageDigest.isEqual(mAuthorization, authorization); }
        Arrays.fill(authorization, (byte) 0);
        if (!authorized) throw new HttpFailure(403);
        if (!jsonContentType(request.headers.get("content-type"))) throw new HttpFailure(415);
        byte[] body = readBody(socket.getInputStream(), length);
        try { UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)); }
        catch (CharacterCodingException invalid) { Arrays.fill(body, (byte) 0); throw new HttpFailure(400); }
        if (!live()) { Arrays.fill(body, (byte) 0); return; }
        if ("/api/cancel".equals(request.path)) {
            Arrays.fill(body, (byte) 0);
            synchronized (mLock) { mCancelled = mConsumed = true; }
            closeQuietly(mServer);
            send(socket, 200, "application/json; charset=utf-8", "{\"cancelled\":true}".getBytes(UTF8));
            return;
        }
        final boolean save = "/api/save".equals(request.path);
        final boolean test = "/api/test".equals(request.path);
        final Ticket ticket = new Ticket(socket, save, save || test, deadline(mClock.nowMs(), BACKEND_MS));
        synchronized (mLock) {
            if (mClosed || mConsumed) { Arrays.fill(body, (byte) 0); return; }
            if (mInFlight != null) { Arrays.fill(body, (byte) 0); throw new HttpFailure(409); }
            if (ticket.exclusive) mInFlight = ticket;
            mWaiting = ticket;
            mPhase = WAITING;
        }
        try {
            if (save) mBackend.save(body, reply -> complete(ticket, reply));
            else if (test) mBackend.test(body, reply -> complete(ticket, reply));
            else { Arrays.fill(body, (byte) 0); mBackend.read(reply -> complete(ticket, reply)); }
        } catch (RuntimeException failure) {
            // A throw after dispatch is ambiguous: keep a save/probe reserved until completion or exit.
            if (!ticket.exclusive) complete(ticket, new Reply(503, ERROR, false));
        }
        Reply reply = awaitReply(ticket);
        if (!live()) return;
        if (reply == null) send(socket, 503, "application/json; charset=utf-8", UNKNOWN.getBytes(UTF8));
        else send(socket, reply.status, "application/json; charset=utf-8", reply.json.getBytes(UTF8));
    }

    private static final class Ticket {
        final Socket socket;
        final boolean save;
        final boolean exclusive;
        final long deadlineMs;
        boolean waiting = true;
        boolean done;
        boolean cancelled;
        Reply reply;
        Ticket(Socket socket, boolean save, boolean exclusive, long deadlineMs) {
            this.socket = socket; this.save = save; this.exclusive = exclusive;
            this.deadlineMs = deadlineMs;
        }
    }

    private void complete(Ticket ticket, Reply original) {
        if (!live()) return;
        boolean closeNow = false;
        synchronized (mLock) {
            if (mClosed || mCancelled || ticket.done || ticket.cancelled) return;
            ticket.reply = !ticket.save && original != null && original.saved ?
                    new Reply(500, ERROR, false) : safeReply(original);
            ticket.done = true;
            if (ticket.exclusive) {
                if (ticket.save && original != null && original.saved) {
                    mSaved = mConsumed = true;
                    closeQuietly(mServer); // Consume only on the backend's real save acknowledgement.
                    closeNow = !ticket.waiting || mActive != ticket.socket;
                    if (!closeNow) {
                        mPhase = WRITING;
                        mSocketDeadline = deadline(mClock.nowMs(), WRITE_MS);
                    }
                } else if (mInFlight == ticket) mInFlight = null;
            }
            mLock.notifyAll();
        }
        if (closeNow) close();
    }

    private Reply awaitReply(Ticket ticket) {
        synchronized (mLock) {
            while (!mClosed && !ticket.done && !ticket.cancelled) {
                long remaining = ticket.deadlineMs - mClock.nowMs();
                if (remaining <= 0) break;
                try { mLock.wait(Math.min(remaining, 100)); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            }
            ticket.waiting = false;
            if (mWaiting == ticket) mWaiting = null;
            return ticket.done && !ticket.cancelled ? ticket.reply : null;
        }
    }

    private static Reply safeReply(Reply reply) {
        if (reply == null || reply.json == null || reply.json.length() > MAX_RESPONSE_BODY ||
                reply.status < 200 || reply.status > 599) return new Reply(500, ERROR, false);
        try {
            int size = UTF8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(reply.json)).remaining();
            if (size > MAX_RESPONSE_BODY) return new Reply(500, ERROR, false);
        } catch (CharacterCodingException invalid) { return new Reply(500, ERROR, false); }
        return reply;
    }

    private static final class Request {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
    }
    private Request readRequest(Socket socket) throws IOException, HttpFailure {
        HeaderReader reader = new HeaderReader(socket.getInputStream());
        String[] first = reader.line().split(" ", -1);
        if (first.length != 3 || !"HTTP/1.1".equals(first[2]) || !first[1].startsWith("/") ||
                first[1].startsWith("//") || first[1].indexOf('?') >= 0 || first[1].indexOf('#') >= 0 ||
                first[1].indexOf('%') >= 0 || first[1].indexOf('\\') >= 0) throw new HttpFailure(400);
        Request request = new Request();
        request.method = first[0];
        request.path = first[1];
        for (int count = 0; ; count++) {
            String line = reader.line();
            if (line.isEmpty()) break;
            if (count >= MAX_FIELDS) throw new HttpFailure(431);
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpFailure(400);
            String key = line.substring(0, colon);
            for (int i = 0; i < key.length(); i++) if (!headerToken(key.charAt(i))) throw new HttpFailure(400);
            key = key.toLowerCase(Locale.US);
            if (request.headers.put(key, line.substring(colon + 1).trim()) != null) throw new HttpFailure(400);
        }
        return request;
    }

    private static final class HeaderReader {
        final InputStream input;
        int total;
        HeaderReader(InputStream input) { this.input = input; }
        int next() throws IOException, HttpFailure {
            int value = input.read();
            if (value < 0) throw new IOException("Incomplete pairing request");
            if (++total > MAX_HEADERS) throw new HttpFailure(431);
            return value;
        }
        String line() throws IOException, HttpFailure {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int value = next();
                if (value == '\r') {
                    if (next() != '\n') throw new HttpFailure(400);
                    return new String(line.toByteArray(), ASCII);
                }
                if (value != '\t' && (value < 32 || value > 126)) throw new HttpFailure(400);
                if (line.size() >= MAX_LINE) throw new HttpFailure(431);
                line.write(value);
            }
        }
    }

    private static byte[] readBody(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        try {
            while (offset < length) {
                int read = input.read(body, offset, length - offset);
                if (read < 0) throw new IOException("Incomplete pairing body");
                offset += read;
            }
            return body;
        } catch (IOException failure) { Arrays.fill(body, (byte) 0); throw failure; }
    }
    private static int contentLength(String text, boolean required) throws HttpFailure {
        if (text == null) { if (required) throw new HttpFailure(411); return 0; }
        if (text.isEmpty() || text.length() > 10) throw new HttpFailure(400);
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') throw new HttpFailure(400);
            length = length * 10 + c - '0';
            if (length > MAX_BODY) throw new HttpFailure(413);
        }
        return (int) length;
    }
    private boolean sameOrigin(Request request) {
        String origin = request.headers.get("origin");
        String referer = request.headers.get("referer");
        if (origin != null && !mOrigin.equals(origin)) return false;
        if (referer != null && !sameOriginReferer(referer)) return false;
        return origin != null || referer != null || "GET".equals(request.method);
    }
    private boolean sameOriginReferer(String referer) {
        if (!referer.startsWith(mOrigin + "/")) return false;
        try {
            URI uri = new URI(referer);
            return "http".equals(uri.getScheme()) && mAuthority.equals(uri.getRawAuthority()) &&
                    uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException invalid) { return false; }
    }
    private static boolean jsonContentType(String value) {
        if (value == null) return false;
        String type = value.replace(" ", "").toLowerCase(Locale.US);
        return "application/json".equals(type) || "application/json;charset=utf-8".equals(type) ||
                "application/json;charset=\"utf-8\"".equals(type);
    }

    private void send(Socket socket, int status, String type, byte[] body) throws IOException {
        if (!live()) return;
        synchronized (mLock) {
            if (mClosed) return;
            mPhase = WRITING;
            mSocketDeadline = deadline(mClock.nowMs(), WRITE_MS);
        }
        String header = "HTTP/1.1 " + status + " Response\r\n" +
                "Content-Type: " + type + "\r\n" + "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\n" +
                "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
                "Cross-Origin-Resource-Policy: same-origin\r\n" +
                "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; " +
                "connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n\r\n";
        byte[] encoded = header.getBytes(ASCII);
        if (encoded.length + body.length > MAX_RESPONSE) throw new IOException("Pairing response too large");
        OutputStream output = socket.getOutputStream();
        output.write(encoded);
        output.write(body);
        output.flush();
    }

    /** RFC 5952 spelling matches browser URL canonicalization before exact Host/Origin checks. */
    static String numericHost(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return address.getHostAddress();
        int[] groups = new int[8];
        int bestStart = -1, bestLength = 1;
        for (int i = 0; i < 8; i++) groups[i] = (bytes[i * 2] & 255) * 256 + (bytes[i * 2 + 1] & 255);
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) { i++; continue; }
            int start = i;
            while (i < 8 && groups[i] == 0) i++;
            if (i - start > bestLength) { bestStart = start; bestLength = i - start; }
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 8; ) {
            if (i == bestStart) { result.append("::"); i += bestLength; continue; }
            if (result.length() != 0 && result.charAt(result.length() - 1) != ':') result.append(':');
            result.append(Integer.toHexString(groups[i++]));
        }
        return "[" + result + "]";
    }

    private static boolean localAddress(InetAddress address, boolean test) {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
        if (address.isLoopbackAddress()) return test;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 254) == 252; // RFC 4193 ULA, not link-local/public.
        if (bytes.length != 4) return false;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
    }
    private static Map<String, Asset> copyAssets(Map<String, Asset> assets) {
        if (assets == null || assets.size() > 3) throw invalid();
        Map<String, Asset> copy = new HashMap<>();
        for (Map.Entry<String, Asset> entry : assets.entrySet()) {
            String mime;
            if ("/".equals(entry.getKey())) mime = "text/html";
            else if ("/pair.js".equals(entry.getKey())) mime = "application/javascript";
            else if ("/pair.css".equals(entry.getKey())) mime = "text/css";
            else throw invalid();
            Asset asset = entry.getValue();
            if (asset == null || asset.contentType == null || asset.bytes == null || asset.bytes.length > MAX_RESPONSE_BODY) throw invalid();
            String type = asset.contentType.replace(" ", "").toLowerCase(Locale.US);
            boolean valid = type.equals(mime) || type.equals(mime + ";charset=utf-8");
            if ("/pair.js".equals(entry.getKey())) valid |= type.equals("text/javascript") || type.equals("text/javascript;charset=utf-8");
            if (!valid) throw invalid();
            copy.put(entry.getKey(), new Asset(mime + "; charset=utf-8", asset.bytes));
        }
        return copy;
    }
    private static boolean headerToken(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' ||
                "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
    private static long deadline(long now, long duration) {
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid pairing configuration"); }
    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }
    private static final class HttpFailure extends Exception {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }
}
