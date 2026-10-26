package com.liskovsoft.smartyoutubetv2.common.subtitle.pairing;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Loopback-only transport fixtures. Never print request text, pairing URLs or generated tokens. */
public class CaptionPairingServerTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final List<Harness> mHarnesses = new ArrayList<>();

    @After public void stopThreads() throws Exception {
        for (Harness harness : mHarnesses) {
            harness.server.close();
            assertTrue("owned transport threads must terminate", harness.server.awaitClosed(2500));
        }
    }

    @Test public void publicEntryRejectsWildcardPublicAndLoopbackBeforeBind() throws Exception {
        for (byte[] address : new byte[][] {
                {0, 0, 0, 0}, {127, 0, 0, 1}, {8, 8, 8, 8}, {(byte) 169, (byte) 254, 1, 1},
                {(byte) 224, 0, 0, 1}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {0x20, 1, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1} }) {
            try {
                CaptionPairingServer.start(InetAddress.getByAddress(address), assets(), () -> 0,
                        () -> true, new FakeBackend(), saved -> { });
                fail("non-private production bind accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void ipv6HostUsesBrowserCanonicalCompressionWithoutDns() throws Exception {
        assertEquals("[fd00::1]", CaptionPairingServer.numericHost(InetAddress.getByAddress(
                new byte[] {(byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertEquals("[::1]", CaptionPairingServer.numericHost(InetAddress.getByAddress(
                new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertEquals("[fd00:1::2:0:0:3]", CaptionPairingServer.numericHost(InetAddress.getByAddress(
                new byte[] {(byte) 0xfd, 0, 0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 3})));
    }

    @Test public void staleGateDoesNotStartListener() throws Exception {
        try {
            CaptionPairingServer.start(loopback(), assets(), () -> 0, () -> false,
                    new FakeBackend(), saved -> { }, 1000);
            fail("stale gate accepted");
        } catch (IOException expected) { }
    }

    @Test public void staticAssetsArePublicImmutableAndNeverContainPairToken() throws Exception {
        Harness h = start();
        String response = h.exchange(h.get("/", ""));
        status(200, response);
        assertTrue(response.endsWith("<html></html>"));
        assertFalse("static assets cannot disclose pairing authorization", response.contains(h.token));
        assertTrue(response.contains("Referrer-Policy: same-origin\r\n"));
        assertTrue(response.contains("Cache-Control: no-store\r\n"));
        assertTrue(response.contains("frame-ancestors 'none'"));
        assertTrue(response.contains("Connection: close\r\n"));
        assertFalse(response.contains("Access-Control-Allow-Origin"));
        status(200, h.exchange(h.get("/pair.js", "")));
        status(200, h.exchange(h.get("/pair.css", "")));
        assertEquals(0, h.backend.reads.get());
        assertEquals(0, h.backend.saves.get());
        assertEquals(0, h.backend.tests.get());

        byte[] original = "before".getBytes(UTF8);
        CaptionPairingServer.Asset asset = new CaptionPairingServer.Asset("text/html", original);
        Map<String, CaptionPairingServer.Asset> supplied = new HashMap<>();
        supplied.put("/", asset);
        Harness isolated = start(new FakeBackend(), supplied);
        Arrays.fill(original, (byte) 'x');
        Arrays.fill(asset.bytes, (byte) 'x');
        supplied.clear();
        assertTrue(isolated.exchange(isolated.get("/", "")).endsWith("before"));
    }

    @Test public void tokenIsRandomHexInFragmentOnlyAndPurgedOnClose() throws Exception {
        Harness one = start(), two = start();
        assertTrue(one.token.matches("[0-9a-f]{64}"));
        assertFalse("independent sessions need independent authorization", one.token.equals(two.token));
        URI url = URI.create(one.server.pairingUrl());
        assertEquals("/", url.getRawPath());
        assertNull(url.getRawQuery());
        one.server.close();
        assertEquals("", one.server.pairingUrl());
        assertTrue(one.server.awaitClosed(2000));
    }

    @Test public void stateRequiresAuthorizationAndSameOriginButRefererCanSubstitute() throws Exception {
        Harness h = start();
        status(403, h.exchange(h.post("/api/state", "{}", "Origin: " + h.server.origin() + "\r\n", false)));
        status(403, h.exchange(h.post("/api/state", "{}", "", true)));
        status(200, h.exchange(h.post("/api/state", "{}", "Referer: " + h.server.origin() + "/\r\n", true)));
        status(200, h.exchange(h.post("/api/state", "{}", h.sameOrigin(), true)));
        assertEquals(2, h.backend.reads.get());
    }

    @Test public void foreignNullAndForgedOriginsNeverReachBackend() throws Exception {
        Harness h = start();
        for (String proof : new String[] {
                "Origin: null\r\n", "Origin: http://example.invalid\r\n",
                "Origin: " + h.server.origin() + ".invalid\r\n",
                "Referer: " + h.server.origin() + ".invalid/\r\n",
                "Referer: " + h.server.origin() + "@example.invalid/\r\n",
                "Referer: " + h.server.origin() + "/#not-a-proof\r\n",
                "Origin: http://example.invalid\r\nReferer: " + h.server.origin() + "/\r\n",
                h.sameOrigin() + "Referer: http://example.invalid/\r\n" }) {
            status(403, h.exchange(h.post("/api/state", "{}", proof, true)));
        }
        status(403, h.exchange(h.get("/", "Origin: http://example.invalid\r\n")));
        assertEquals(0, h.backend.reads.get());
    }

    @Test public void wrongHostAndWrongAuthorizationNeverReachBackend() throws Exception {
        Harness h = start();
        status(403, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)
                .replace("Host: " + h.authority, "Host: example.invalid")));
        String request = h.post("/api/save", "{}", h.sameOrigin(), false);
        status(403, h.exchange(request.replace("Content-Type:", "Authorization: Pair invalid\r\nContent-Type:")));
        assertEquals(0, h.backend.saves.get());
    }

    @Test public void strictFramingRejectsDuplicatesTransferEncodingCompressionAndExpect() throws Exception {
        Harness h = start();
        for (String extra : new String[] {
                "Host: " + h.authority + "\r\n", "content-length: 2\r\n",
                "Authorization: Pair invalid\r\n", "Origin: " + h.server.origin() + "\r\n",
                "Transfer-Encoding: chunked\r\n", "Transfer-Encoding: identity\r\n",
                "Content-Encoding: gzip\r\n", "Expect: 100-continue\r\n",
                " Folded: rejected\r\n" }) {
            String request = h.post("/api/save", "{}", h.sameOrigin() + extra, true);
            int expected = extra.startsWith("Content-Encoding:") ? 415 : extra.startsWith("Expect:") ? 417 : 400;
            status(expected, h.exchange(request));
        }
        assertEquals(0, h.backend.saves.get());
    }

    @Test public void exactMethodsPathsAndJsonMediaAreEnforced() throws Exception {
        Harness h = start();
        status(404, h.exchange(h.get("/missing", "")));
        status(404, h.exchange(h.post("/api/other", "{}", h.sameOrigin(), true)));
        status(405, h.exchange(h.get("/", h.sameOrigin()).replace("GET / ", "OPTIONS / ")));
        for (String target : new String[] {"/?x=1", "/#fragment", "//example.invalid/", "/%2f", "http://example.invalid/"}) {
            status(400, h.exchange(h.get("/", "").replace("GET / ", "GET " + target + " ")));
        }
        status(415, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)
                .replace("application/json; charset=utf-8", "text/plain")));
        assertEquals(0, h.backend.saves.get());
    }

    @Test public void lengthAndUtf8BoundariesAreRejectedBeforeBackend() throws Exception {
        Harness h = start();
        String base = h.post("/api/save", "{}", h.sameOrigin(), true);
        status(411, h.exchange(base.replace("Content-Length: 2\r\n", "")));
        status(400, h.exchange(base.replace("Content-Length: 2", "Content-Length: +2")));
        status(400, h.exchange(base.replace("Content-Length: 2", "Content-Length: 2,2")));
        status(413, h.exchange(base.replace("Content-Length: 2", "Content-Length: 32769")));
        status(400, h.exchange(h.get("/", "Content-Length: 1\r\n") + "x"));
        byte[] request = base.getBytes(UTF8);
        request[request.length - 2] = (byte) 0xc3;
        request[request.length - 1] = (byte) 0x28;
        status(400, h.exchange(request));
        assertEquals(0, h.backend.saves.get());
    }

    @Test public void headerLineTotalAndFieldLimitsAreBounded() throws Exception {
        Harness h = start();
        status(431, h.exchange(h.get("/", "X-Large: " + repeat('a', 2049) + "\r\n")));
        StringBuilder total = new StringBuilder();
        for (int i = 0; i < 10; i++) total.append("X-").append(i).append(": ").append(repeat('b', 1000)).append("\r\n");
        status(431, h.exchange(h.get("/", total.toString())));
        StringBuilder fields = new StringBuilder();
        for (int i = 0; i < 32; i++) fields.append("X-").append(i).append(": 1\r\n");
        status(431, h.exchange(h.get("/", fields.toString())));
    }

    @Test public void oneRequestPerSocketNeverProcessesPipelinedMutation() throws Exception {
        Harness h = start();
        String response = h.exchange(h.get("/", "") + h.post("/api/save", "{}", h.sameOrigin(), true));
        status(200, response);
        assertEquals(1, occurrences(response, "HTTP/1.1"));
        assertEquals(0, h.backend.saves.get());
    }

    @Test public void realSaveAcknowledgementFlushesThenClosesAndNotifiesExactlyOnce() throws Exception {
        Harness h = start();
        h.backend.saveReply = new CaptionPairingServer.Reply(200, "{\"saved\":true,\"keyState\":\"MEMORY_ONLY\"}", true);
        String response = h.exchange(h.post("/api/save", "{\"fixture\":true}", h.sameOrigin(), true));
        status(200, response);
        assertTrue(response.endsWith(h.backend.saveReply.json));
        assertTrue(h.server.awaitClosed(2000));
        assertTrue(h.server.isClosed());
        assertEquals(1, h.notifications.get());
        assertTrue(h.notifiedSaved.get());
        h.server.close();
        assertEquals(1, h.notifications.get());
        assertEquals(1, h.backend.saves.get());
    }

    @Test public void validationAndPersistenceFailureDoNotSpendTokenAndCanRetry() throws Exception {
        Harness h = start();
        for (int failure : new int[] {400, 409, 503}) {
            h.backend.saveReply = new CaptionPairingServer.Reply(failure, "{\"saved\":false}", false);
            status(failure, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
            assertFalse(h.server.isClosed());
            assertTrue("authorization remains valid after failure", h.server.pairingUrl().endsWith(h.token));
        }
        h.backend.saveReply = new CaptionPairingServer.Reply(200, "{\"saved\":true}", true);
        status(200, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
        assertTrue(h.server.awaitClosed(2000));
        assertEquals(4, h.backend.saves.get());
    }

    @Test public void explicitTestIsNotAutomaticDoesNotSpendTokenAndCanBeFollowedBySave() throws Exception {
        Harness h = start();
        h.exchange(h.get("/", ""));
        h.exchange(h.post("/api/state", "{}", h.sameOrigin(), true));
        assertEquals(0, h.backend.tests.get());
        status(200, h.exchange(h.post("/api/test", "{\"fixture\":true}", h.sameOrigin(), true)));
        assertEquals(1, h.backend.tests.get());
        assertFalse(h.server.isClosed());
        h.backend.saveReply = new CaptionPairingServer.Reply(200, "{\"saved\":true}", true);
        status(200, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
        assertTrue(h.server.awaitClosed(2000));
        assertEquals(1, h.backend.saves.get());
    }

    @Test public void unsupportedTestIsSafe501WithoutConsumingToken() throws Exception {
        CaptionPairingServer.Backend backend = new CaptionPairingServer.Backend() {
            @Override public void read(CaptionPairingServer.Completion completion) { completion.complete(ok()); }
            @Override public void save(byte[] body, CaptionPairingServer.Completion completion) { completion.complete(ok()); }
        };
        Harness h = start(backend, assets());
        status(501, h.exchange(h.post("/api/test", "{}", h.sameOrigin(), true)));
        assertFalse(h.server.isClosed());
        status(200, h.exchange(h.post("/api/state", "{}", h.sameOrigin(), true)));
    }

    @Test public void testMisreportingSavedCannotConsumeOrClosePairing() throws Exception {
        Harness h = start();
        h.backend.testReply = new CaptionPairingServer.Reply(200, "{\"saved\":true}", true);
        status(500, h.exchange(h.post("/api/test", "{}", h.sameOrigin(), true)));
        assertFalse(h.server.isClosed());
        assertEquals(0, h.notifications.get());
        status(200, h.exchange(h.post("/api/state", "{}", h.sameOrigin(), true)));
    }

    @Test public void asynchronousSaveWaitsForActualCompletionAndIgnoresDuplicateCompletion() throws Exception {
        Harness h = start();
        h.backend.delaySave = true;
        try (Socket socket = h.open(h.post("/api/save", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            assertFalse(h.server.isClosed());
            CaptionPairingServer.Completion done = h.backend.pending.get();
            done.complete(new CaptionPairingServer.Reply(200, "{\"saved\":true}", true));
            done.complete(new CaptionPairingServer.Reply(503, "{}", false));
            status(200, readResponse(socket));
        }
        assertTrue(h.server.awaitClosed(2000));
        assertTrue(h.notifiedSaved.get());
        assertEquals(1, h.backend.saves.get());
    }

    @Test public void resetClientBeforeSaveAckCannotRetainListenerOrRepeatWrite() throws Exception {
        Harness h = start();
        h.backend.delaySave = true;
        Socket socket = h.open(h.post("/api/save", "{}", h.sameOrigin(), true));
        assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
        socket.setSoLinger(true, 0);
        socket.close();
        assertFalse(h.server.awaitClosed(0));
        h.backend.pending.get().complete(new CaptionPairingServer.Reply(200, "{\"saved\":true}", true));
        assertTrue(h.server.awaitClosed(2000));
        assertTrue(h.notifiedSaved.get());
        assertEquals(1, h.backend.saves.get());
    }

    @Test public void timedOutSaveRemainsReservedUntilRealFailureThenAllowsRetry() throws Exception {
        Harness h = start();
        h.backend.delaySave = true;
        try (Socket first = h.open(h.post("/api/save", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.clock.addAndGet(10001);
            String response = readResponse(first);
            status(503, response);
            assertTrue(response.endsWith("{\"error\":\"result_unknown\"}"));
        }
        status(409, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
        status(409, h.exchange(h.post("/api/test", "{}", h.sameOrigin(), true)));
        assertEquals(1, h.backend.saves.get());
        assertEquals(0, h.backend.tests.get());
        h.backend.pending.get().complete(new CaptionPairingServer.Reply(503, "{}", false));
        h.backend.delaySave = false;
        h.backend.saveReply = new CaptionPairingServer.Reply(200, "{\"saved\":true}", true);
        status(200, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
        assertTrue(h.server.awaitClosed(2000));
    }

    @Test public void lostSaveAckStillConsumesAndStopsListenerOnLateSuccess() throws Exception {
        Harness h = start();
        h.backend.delaySave = true;
        try (Socket first = h.open(h.post("/api/save", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.clock.addAndGet(10001);
            status(503, readResponse(first));
        }
        h.backend.pending.get().complete(new CaptionPairingServer.Reply(200, "{\"saved\":true}", true));
        assertTrue(h.server.awaitClosed(2000));
        assertTrue(h.notifiedSaved.get());
        assertEquals(1, h.backend.saves.get());
    }

    @Test public void timedOutTestMutuallyExcludesSaveAndLateTestSuccessOnlyReleasesReservation() throws Exception {
        Harness h = start();
        h.backend.delayTest = true;
        try (Socket first = h.open(h.post("/api/test", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.clock.addAndGet(10001);
            status(503, readResponse(first));
        }
        status(409, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
        status(409, h.exchange(h.post("/api/test", "{}", h.sameOrigin(), true)));
        assertEquals(0, h.backend.saves.get());
        assertEquals(1, h.backend.tests.get());
        h.backend.pending.get().complete(ok());
        assertFalse(h.server.isClosed());
        h.backend.delayTest = false;
        status(200, h.exchange(h.post("/api/test", "{}", h.sameOrigin(), true)));
        assertEquals(2, h.backend.tests.get());
    }

    @Test public void closeWakesPendingSaveAndLateCallbackCannotReopenOrReportSaved() throws Exception {
        Harness h = start();
        h.backend.delaySave = true;
        try (Socket socket = h.open(h.post("/api/save", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.server.close();
            assertTrue(h.server.awaitClosed(2000));
            h.backend.pending.get().complete(new CaptionPairingServer.Reply(200, "{\"saved\":true}", true));
            assertTrue(h.server.isClosed());
            assertFalse(h.notifiedSaved.get());
            assertEquals(1, h.notifications.get());
            assertEquals("", readResponse(socket));
        }
    }

    @Test public void expiredPendingTestClosesThreadsAndIgnoresLateCallback() throws Exception {
        Harness h = start();
        h.backend.delayTest = true;
        try (Socket socket = h.open(h.post("/api/test", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.clock.addAndGet(300001);
            assertTrue(h.server.awaitClosed(2000));
            h.backend.pending.get().complete(ok());
            assertTrue(h.server.isClosed());
            assertFalse(h.notifiedSaved.get());
            assertEquals("", readResponse(socket));
        }
    }

    @Test public void gateInvalidationClosesActivePartialReadWithoutInterpretingItsReason() throws Exception {
        Harness h = start();
        try (Socket socket = h.open("GET / HTTP/1.1\r\n")) {
            awaitActive(h.server);
            h.current.set(false);
            assertTrue(h.server.awaitClosed(2000));
            assertEquals("", readResponse(socket));
            assertEquals(0, h.backend.reads.get());
        }
    }

    @Test public void absoluteReadDeadlineClosesOnlyOffendingSocketAndKeepsSessionRetryable() throws Exception {
        Harness h = start();
        try (Socket socket = h.open("GET / HTTP/1.1\r\nHost: ")) {
            awaitActive(h.server);
            h.clock.addAndGet(8001);
            assertEquals("", readResponse(socket));
        }
        assertFalse(h.server.isClosed());
        status(200, h.exchange(h.get("/", "")));
    }

    @Test public void idleReadDeadlineClosesTruncatedBodyAndAllowsRetry() throws Exception {
        Harness h = start();
        String request = h.post("/api/save", "{}", h.sameOrigin(), true);
        try (Socket socket = h.open(request.substring(0, request.length() - 1))) {
            socket.setSoTimeout(5000);
            assertEquals("", readResponse(socket));
        }
        assertEquals(0, h.backend.saves.get());
        status(200, h.exchange(h.get("/", "")));
    }

    @Test public void ttlClosesListenerEvenWhenNoClientConnects() throws Exception {
        Harness h = start();
        h.clock.addAndGet(300001);
        assertTrue(h.server.awaitClosed(2000));
        assertTrue(h.server.isClosed());
        assertEquals(1, h.notifications.get());
        assertFalse(h.notifiedSaved.get());
    }

    @Test public void authenticatedCancelStopsWithoutSaving() throws Exception {
        Harness h = start();
        status(200, h.exchange(h.post("/api/cancel", "{}", h.sameOrigin(), true)));
        assertTrue(h.server.awaitClosed(2000));
        assertFalse(h.notifiedSaved.get());
        assertEquals(0, h.backend.saves.get());
    }

    @Test public void cancelAfterTimeoutSuppressesLateSaveCallback() throws Exception {
        Harness h = start();
        h.backend.delaySave = true;
        try (Socket socket = h.open(h.post("/api/save", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.clock.addAndGet(10001);
            status(503, readResponse(socket));
        }
        status(200, h.exchange(h.post("/api/cancel", "{}", h.sameOrigin(), true)));
        h.backend.pending.get().complete(new CaptionPairingServer.Reply(200, "{\"saved\":true}", true));
        assertTrue(h.server.awaitClosed(2000));
        assertFalse(h.notifiedSaved.get());
    }

    @Test public void backendThrowAfterDispatchCannotReleaseSaveReservation() throws Exception {
        Harness h = start();
        h.backend.throwSave = true;
        try (Socket socket = h.open(h.post("/api/save", "{}", h.sameOrigin(), true))) {
            assertTrue(h.backend.dispatched.await(2, TimeUnit.SECONDS));
            h.clock.addAndGet(10001);
            status(503, readResponse(socket));
        }
        status(409, h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true)));
        assertEquals(1, h.backend.saves.get());
    }

    @Test public void invalidBackendReplyIsBoundedButRealSaveStillCannotBeRepeated() throws Exception {
        Harness h = start();
        h.backend.saveReply = new CaptionPairingServer.Reply(200, repeat('x', 65536), true);
        String response = h.exchange(h.post("/api/save", "{}", h.sameOrigin(), true));
        status(500, response);
        assertTrue(response.getBytes(UTF8).length <= 65536);
        assertTrue(h.server.awaitClosed(2000));
        assertTrue(h.notifiedSaved.get());
    }

    private Harness start() throws Exception { return start(new FakeBackend(), assets()); }
    private Harness start(CaptionPairingServer.Backend backend, Map<String, CaptionPairingServer.Asset> assets) throws Exception {
        Harness h = new Harness(backend, assets);
        mHarnesses.add(h);
        return h;
    }
    private static InetAddress loopback() throws Exception { return InetAddress.getByAddress(new byte[] {127, 0, 0, 1}); }
    private static Map<String, CaptionPairingServer.Asset> assets() {
        Map<String, CaptionPairingServer.Asset> assets = new HashMap<>();
        assets.put("/", new CaptionPairingServer.Asset("text/html; charset=utf-8", "<html></html>".getBytes(UTF8)));
        assets.put("/pair.js", new CaptionPairingServer.Asset("application/javascript", "'use strict';".getBytes(UTF8)));
        assets.put("/pair.css", new CaptionPairingServer.Asset("text/css", "body{}".getBytes(UTF8)));
        return assets;
    }
    private static CaptionPairingServer.Reply ok() { return new CaptionPairingServer.Reply(200, "{}", false); }
    private static final class FakeBackend implements CaptionPairingServer.Backend {
        final AtomicInteger reads = new AtomicInteger(), saves = new AtomicInteger(), tests = new AtomicInteger();
        final CountDownLatch dispatched = new CountDownLatch(1);
        final AtomicReference<CaptionPairingServer.Completion> pending = new AtomicReference<>();
        volatile boolean delaySave, delayTest, throwSave;
        volatile CaptionPairingServer.Reply saveReply = ok(), testReply = ok();
        @Override public void read(CaptionPairingServer.Completion completion) {
            reads.incrementAndGet(); completion.complete(ok());
        }
        @Override public void save(byte[] body, CaptionPairingServer.Completion completion) {
            saves.incrementAndGet(); pending.set(completion); dispatched.countDown();
            Arrays.fill(body, (byte) 0);
            if (throwSave) throw new IllegalStateException("Fixture dispatch failure");
            if (!delaySave) completion.complete(saveReply);
        }
        @Override public void test(byte[] body, CaptionPairingServer.Completion completion) {
            tests.incrementAndGet(); pending.set(completion); dispatched.countDown();
            Arrays.fill(body, (byte) 0);
            if (!delayTest) completion.complete(testReply);
        }
    }
    private static final class Harness {
        final AtomicLong clock = new AtomicLong(1000);
        final AtomicBoolean current = new AtomicBoolean(true), notifiedSaved = new AtomicBoolean();
        final AtomicInteger notifications = new AtomicInteger();
        final CaptionPairingServer server;
        final FakeBackend backend;
        final String authority, token;
        final int port;
        Harness(CaptionPairingServer.Backend backend, Map<String, CaptionPairingServer.Asset> assets) throws Exception {
            this.backend = backend instanceof FakeBackend ? (FakeBackend) backend : null;
            server = CaptionPairingServer.start(loopback(), assets, clock::get, current::get, backend,
                    saved -> { notifiedSaved.set(saved); notifications.incrementAndGet(); }, 300000);
            URI origin = URI.create(server.origin());
            authority = origin.getRawAuthority();
            port = origin.getPort();
            token = URI.create(server.pairingUrl()).getRawFragment();
        }
        String sameOrigin() { return "Origin: " + server.origin() + "\r\n"; }
        String get(String path, String extra) { return "GET " + path + " HTTP/1.1\r\nHost: " + authority + "\r\n" + extra + "\r\n"; }
        String post(String path, String body, String proof, boolean auth) {
            return "POST " + path + " HTTP/1.1\r\nHost: " + authority + "\r\n" + proof +
                    (auth ? "Authorization: Pair " + token + "\r\n" : "") +
                    "Content-Type: application/json; charset=utf-8\r\nContent-Length: " + body.getBytes(UTF8).length + "\r\n\r\n" + body;
        }
        Socket open(String request) throws Exception { return open(request.getBytes(UTF8)); }
        Socket open(byte[] request) throws Exception {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(loopback(), port), 2000);
            socket.setSoTimeout(2500);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return socket;
        }
        String exchange(String request) throws Exception { return exchange(request.getBytes(UTF8)); }
        String exchange(byte[] request) throws Exception { try (Socket socket = open(request)) { return readResponse(socket); } }
    }
    private static String readResponse(Socket socket) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            InputStream input = socket.getInputStream();
            int length;
            while ((length = input.read(buffer)) >= 0) {
                response.write(buffer, 0, length);
                if (response.size() > 65536) throw new IOException("Fixture response exceeded transport bound");
            }
        } catch (SocketException reset) { /* close may reset a socket with unread fixture bytes. */ }
        return new String(response.toByteArray(), UTF8);
    }
    private static void status(int expected, String response) {
        // Do not put a whole response/request or authorization into an assertion message.
        assertTrue("HTTP status line missing", response.startsWith("HTTP/1.1 "));
        assertEquals(expected, Integer.parseInt(response.substring(9, 12)));
    }
    private static String repeat(char c, int n) { char[] text = new char[n]; Arrays.fill(text, c); return new String(text); }
    private static int occurrences(String text, String part) {
        int result = 0, at = 0;
        while ((at = text.indexOf(part, at)) >= 0) { result++; at += part.length(); }
        return result;
    }
    private static void awaitActive(CaptionPairingServer server) throws Exception {
        Field lock = CaptionPairingServer.class.getDeclaredField("mLock");
        Field active = CaptionPairingServer.class.getDeclaredField("mActive");
        lock.setAccessible(true); active.setAccessible(true);
        Object monitor = lock.get(server);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            synchronized (monitor) { if (active.get(server) != null) return; }
            Thread.sleep(5);
        }
        fail("fixture socket was not accepted");
    }
}
