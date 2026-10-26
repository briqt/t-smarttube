package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.liskovsoft.smartyoutubetv2.common.subtitle.pairing.CaptionPairingServer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionEdit;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatTranslationClient;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Calls the production backend without starting any listener or contacting any network. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptionPairingDialogTest {
    private static final String KEY = "synthetic-pairing-dialog-not-a-real-key";
    @Test public void stateIsAsyncAndNeverEchoesAReplacementKey() throws Exception {
        try (Host host = new Host()) {
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            CaptionPairingDialog.Backend backend = backend(host, session);
            AtomicReference<CaptionPairingServer.Reply> reply = new AtomicReference<>();
            backend.read(reply::set);
            assertNull(reply.get()); idle();
            assertEquals(200, reply.get().status);
            assertFalse(reply.get().saved);
            JSONObject state = new JSONObject(reply.get().json);
            assertEquals("0", state.getString("revision"));
            assertFalse(state.has("key"));
            assertFalse(reply.get().json.contains(KEY));
            session.close();
        }
    }
    @Test public void saveClearsBodyAndOnlyConsumesPairingAfterRealAcknowledgement() throws Exception {
        try (Host host = new Host()) {
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            AtomicReference<CaptionPairingServer.Reply> reply = new AtomicReference<>();
            byte[] bytes = form("save");
            backend(host, session).save(bytes, reply::set); idle();
            assertNull(reply.get());
            assertNotNull(host.pending);
            assertEquals(KEY, host.pending.replacement());
            assertArrayEquals(new byte[bytes.length], bytes);
            host.complete(new CaptionPairingDialog.SaveResult(false, true, false, "PARTIAL", host.store.load()));
            assertFalse(reply.get().saved);
            assertTrue(new JSONObject(reply.get().json).getBoolean("partial"));
            assertNull(host.pending.replacement());
            // Failure has not invalidated the session; a retry is dispatched normally.
            reply.set(null);
            backend(host, session).save(form("save"), reply::set); idle();
            assertEquals(2, host.saves);
            host.complete(new CaptionPairingDialog.SaveResult(true, false, false, "SAVED", host.store.load()));
            assertTrue(reply.get().saved);
            assertTrue("Only the HTTP core closes after flush", session.current());
            session.close();
        }
    }
    @Test public void closeAndOwnerChangeBeforeUiDispatchWipeBodiesWithoutCallingHost() throws Exception {
        try (Host host = new Host()) {
            AtomicBoolean owner = new AtomicBoolean(true);
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(owner::get);
            byte[] bytes = form("save");
            AtomicReference<CaptionPairingServer.Reply> reply = new AtomicReference<>();
            backend(host, session).save(bytes, reply::set);
            owner.set(false); session.close(); idle();
            assertArrayEquals(new byte[bytes.length], bytes);
            assertEquals(0, host.reads);
            assertEquals(0, host.saves);
            assertFalse(reply.get().saved);
            assertEquals(409, reply.get().status);
        }
    }
    @Test public void lateSaveAcknowledgementCannotConsumeOrNavigateAClosedWindow() throws Exception {
        try (Host host = new Host()) {
            CaptionPairingDialog.Session old = new CaptionPairingDialog.Session(() -> true);
            CaptionPairingDialog.Session next = new CaptionPairingDialog.Session(() -> true);
            AtomicReference<CaptionPairingServer.Reply> reply = new AtomicReference<>();
            backend(host, old).save(form("save"), reply::set); idle();
            CaptionConnectionEdit pending = host.pending;
            old.close();
            assertNull(pending.replacement());
            host.complete(new CaptionPairingDialog.SaveResult(true, false, false, "SAVED", host.store.load()));
            assertFalse(reply.get().saved);
            assertEquals(409, reply.get().status);
            assertTrue(next.current()); next.close();
        }
    }
    @Test public void routesRejectWrongOperationAndStaleRevisionBeforeSavingOrProbing() throws Exception {
        try (Host host = new Host()) {
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            CaptionPairingDialog.Backend backend = backend(host, session);
            AtomicReference<CaptionPairingServer.Reply> reply = new AtomicReference<>();
            backend.save(form("test"), reply::set); idle(); assertEquals(400, reply.get().status);
            backend.test(form("save"), reply::set); idle(); assertEquals(400, reply.get().status);
            byte[] stale = new JSONObject(new String(form("save"), Charset.forName("UTF-8"))).put("revision", "9007199254740993")
                    .toString().getBytes(Charset.forName("UTF-8"));
            backend.save(stale, reply::set); idle(); assertEquals(409, reply.get().status);
            assertEquals(0, host.saves); assertEquals(0, host.probes);
            assertArrayEquals(new byte[stale.length], stale);
            session.close();
        }
    }
    @Test public void clearWholeConnectionUsesExplicitOperationWithoutInventingFormFields() throws Exception {
        try (Host host = new Host()) {
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            byte[] body = "{\"version\":1,\"revision\":\"0\",\"operation\":\"clear\"}".getBytes(Charset.forName("UTF-8"));
            backend(host, session).save(body, ignored -> { }); idle();
            assertEquals(CaptionConnectionEdit.Operation.CLEAR, host.pending.operation);
            assertNull(host.pending.connection);
            assertNull(host.pending.keyEdit);
            assertArrayEquals(new byte[body.length], body);
            session.close();
        }
    }
    @Test public void explicitProbeFailureIsSanitizedAndNeverReportsSaved() throws Exception {
        try (Host host = new Host()) {
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            AtomicReference<CaptionPairingServer.Reply> reply = new AtomicReference<>();
            backend(host, session).test(form("test"), reply::set); idle();
            assertEquals(1, host.probes); assertEquals(0, host.saves);
            assertFalse(reply.get().saved);
            JSONObject json = new JSONObject(reply.get().json);
            assertFalse(json.getBoolean("ok")); assertEquals("TEST_FAILED", json.getString("code"));
            assertEquals(0, json.getInt("httpCode")); assertEquals(3, json.length());
            assertFalse(reply.get().json.contains(KEY));
            session.close();
        }
    }
    @Test public void truncatedProviderFinishNeverReportsTestOkEvenWhenEveryItemWasDecoded() throws Exception {
        java.lang.reflect.Constructor<?> constructor = ChatTranslationClient.Result.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ChatTranslationClient.Result result = (ChatTranslationClient.Result) constructor.newInstance(null,
                ChatTranslationClient.Status.COMPLETE, java.util.Collections.emptyMap(), 1, null, 200,
                com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter.State.TRUNCATED);
        CaptionPairingServer.Reply reply = CaptionPairingDialog.probeReply(result);
        JSONObject body = new JSONObject(reply.json);
        assertFalse(body.getBoolean("ok"));
        assertEquals("TEST_TRUNCATED", body.getString("code"));
        assertEquals(200, body.getInt("httpCode"));
        assertFalse(reply.saved);
        assertEquals(3, body.length());
    }
    @Test public void sessionCloseDiscardsAnAlreadyIssuedKeepTestCredentialWithoutRevokingSavedKey() throws Exception {
        try (Host host = new Host()) {
            CaptionConnectionEdit replacement = CaptionConnectionEdit.parse(form("save"), null);
            CaptionConnectionStore.Snapshot saved = host.store.save(host.store.load(), replacement.connection, replacement.keyEdit, replacement.replacement());
            replacement.discard();
            JSONObject body = new JSONObject(new String(form("test"), Charset.forName("UTF-8")))
                    .put("revision", Long.toString(saved.revision)).put("keyEdit", "KEEP").put("key", JSONObject.NULL);
            CaptionConnectionEdit keep = CaptionConnectionEdit.parse(body.toString().getBytes(Charset.forName("UTF-8")), saved.connection);
            ChatTranslationClient.Credentials credential = keep.testCredentials(saved, "zh");
            assertNotNull(credential);
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            assertTrue(session.hold(keep));
            ChatTranslationClient.Job job = com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionProbe.create(keep, saved, "zh");
            assertTrue(session.hold(job));
            session.close();
            ChatTranslationClient.Result cancelled = job.execute(); // Cancellation precedes execute, so zero calls and no socket.
            assertEquals(ChatTranslationClient.Status.CANCELLED, cancelled.status);
            assertEquals(0, cancelled.calls);
            assertNull(credential.getKey());
            assertEquals(KEY, saved.profile("zh").credentials.getKey());
        }
    }
    @Test @Config(sdk = 17) public void qrEncoderRoundTripsOfflineOnMin17WithoutCameraOrWebApi() throws Exception {
        String fixture = "http://192.168.20.5:43210/#synthetic-pairing-token-not-a-key";
        Bitmap bitmap = CaptionPairingDialog.qr(fixture);
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight(); int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            assertEquals(fixture, new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)))).getText());
        } finally { bitmap.recycle(); }
    }
    @Test public void addressPolicyAcceptsPrivateV4AndUlaButRejectsPublicCellularStyleAndLoopback() throws Exception {
        assertTrue(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(new byte[]{10, 4, 1, 2})));
        assertTrue(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 3, 4})));
        assertFalse(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})));
        assertFalse(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(new byte[]{0, 0, 0, 0})));
        assertFalse(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(new byte[]{100, 64, 1, 2})));
        byte[] ipv6 = new byte[16]; ipv6[0] = (byte) 0xfd; ipv6[15] = 1;
        assertTrue(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(ipv6)));
        ipv6[0] = (byte) 0xfe; ipv6[1] = (byte) 0x80;
        assertFalse(CaptionPairingDialog.privateAddress(InetAddress.getByAddress(ipv6)));
    }
    @Test public void legacyWifiUlaOnlyMatchesPhysicalIdentityWithoutRequiringIpv4() throws Exception {
        byte[] ulaBytes = new byte[16]; ulaBytes[0] = (byte) 0xfd; ulaBytes[15] = 7;
        InetAddress ula = InetAddress.getByAddress(ulaBytes);
        byte[] hardware = {0x12, 0x34, 0x56, 0x78, (byte) 0x9a, (byte) 0xbc};
        assertTrue("Connected Wi-Fi with zero IPv4 must retain its physical ULA address",
                matchesWifiAddress(true, 0, "12:34:56:78:9A:BC", hardware, ula));
        assertFalse("A matching MAC is not enough when Wi-Fi is disconnected",
                matchesWifiAddress(false, 0, "12:34:56:78:9a:bc", hardware, ula));
        assertFalse("A different physical interface must not be mistaken for Wi-Fi",
                matchesWifiAddress(true, 0, "12:34:56:78:9a:bd", hardware, ula));
        assertFalse(matchesWifiAddress(true, 0, null, hardware, ula));
        assertFalse(matchesWifiAddress(true, 0, "12:34:56:78:9a:bc", null, ula));
        assertFalse(matchesWifiAddress(true, 0, "not-a-mac", hardware, ula));
        InetAddress ipv4 = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 3, 4});
        assertTrue("Existing IPv4 matching remains usable when MAC is unavailable",
                matchesWifiAddress(true, 0x0403a8c0, null, null, ipv4));
    }
    /** Reflection keeps the red test compilable before the pure production matching seam is added. */
    private static boolean matchesWifiAddress(boolean connected, int ipv4, String wifiMac, byte[] hardware, InetAddress address) throws Exception {
        java.lang.reflect.Method method;
        try {
            method = CaptionPairingDialog.class.getDeclaredMethod("matchesWifiAddress", boolean.class, int.class,
                    String.class, byte[].class, InetAddress.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Missing pure legacy Wi-Fi identity matcher: current IPv4-only branch drops ULA-only Wi-Fi", missing);
        }
        method.setAccessible(true);
        try { return (Boolean) method.invoke(null, connected, ipv4, wifiMac, hardware, address); }
        catch (java.lang.reflect.InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    private static CaptionPairingDialog.Backend backend(Host host, CaptionPairingDialog.Session session) {
        return new CaptionPairingDialog.Backend(new Handler(Looper.getMainLooper()), session, host,
                task -> { throw new AssertionError("This fixture must not execute a probe"); });
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static byte[] form(String operation) throws Exception {
        return new JSONObject().put("version", 1).put("revision", "0").put("operation", operation)
                .put("endpoint", "https://fixture.invalid/chat").put("model", "fixture-model").put("tokens", 1024)
                .put("thinking", "OMIT").put("keyEdit", "REPLACE").put("key", KEY).toString().getBytes(Charset.forName("UTF-8"));
    }
    private static final class Host implements CaptionPairingDialog.Host, AutoCloseable {
        final CaptionConnectionStore store = new CaptionConnectionStore(RuntimeEnvironment.getApplication(), "pairing-dialog-" + UUID.randomUUID());
        int reads, saves, probes;
        CaptionConnectionEdit pending;
        CaptionPairingDialog.Saved completion;
        @Override public void read(CaptionPairingDialog.Read completion) { reads++; completion.complete(store.load()); }
        @Override public void save(CaptionConnectionEdit edit, CaptionPairingDialog.Session session, CaptionPairingDialog.Saved completion) {
            saves++; pending = edit; this.completion = completion;
        }
        @Override public ChatTranslationClient.Job probe(CaptionConnectionEdit edit, CaptionConnectionStore.Snapshot saved) { probes++; return null; }
        void complete(CaptionPairingDialog.SaveResult result) { completion.complete(result); }
        @Override public void close() { store.close(); }
    }
}
