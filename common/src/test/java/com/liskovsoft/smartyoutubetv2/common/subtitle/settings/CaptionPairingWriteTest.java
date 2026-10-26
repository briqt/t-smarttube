package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.content.SharedPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferenceStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Synthetic stores only. No sockets, device, provider calls or real credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionPairingWriteTest {
    private static final String KEY = "synthetic-pairing-write-not-a-real-key";

    @Test public void closeBeforeCommitDoesNotWriteOrMarkAWriteFailure() {
        try (Stores stores = new Stores()) {
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(() -> true);
            CaptionConnectionStore.Snapshot before = stores.connection.load();
            session.close();
            CaptionSettingsController.ConnectionWrite result = write(stores, before, session::beginCommit);
            assertTrue(result.result.cancelled);
            assertFalse(result.result.saved);
            assertFalse(result.result.partial);
            assertEquals("CANCELLED", result.result.code);
            assertEquals(before.revision, stores.connection.load().revision);
            assertNull(stores.connection.load().connection);
            assertNull(stores.preferences.load().preferences.global.connectionId);
        }
    }
    @Test public void profileChangeBeforeCommitRejectsQueuedOldScopeSave() {
        try (Stores old = new Stores(); Stores next = new Stores()) {
            AtomicBoolean active = new AtomicBoolean(true);
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(active::get);
            CaptionConnectionStore.Snapshot captured = old.connection.load();
            active.set(false);
            CaptionSettingsController.ConnectionWrite result = write(old, captured, session::beginCommit);
            assertTrue(result.result.cancelled);
            assertEquals(0, old.connection.load().revision);
            assertEquals(0, next.connection.load().revision);
        }
    }
    @Test public void profileAndCloseAfterCommitStartsOnlyFinishTheCapturedScope() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (Stores old = new Stores(); Stores next = new Stores()) {
            AtomicBoolean active = new AtomicBoolean(true);
            CaptionPairingDialog.Session session = new CaptionPairingDialog.Session(active::get);
            CaptionConnectionStore.Snapshot captured = old.connection.load();
            Future<CaptionSettingsController.ConnectionWrite> running = io.submit(() -> CaptionSettingsController.writeConnection(
                    old.connection, old.preferences, captured, config(), CaptionConnectionStore.KeyEdit.REPLACE, () -> {
                        started.countDown();
                        try { if (!finish.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture barrier timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture interrupted"); }
                        return KEY;
                    }, false, session::beginCommit));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            active.set(false); session.close(); finish.countDown();
            CaptionSettingsController.ConnectionWrite result = running.get(3, TimeUnit.SECONDS);
            assertTrue("Started writes are not claimed to be revoked", result.result.saved);
            assertFalse(session.current());
            assertEquals(1, old.connection.load().revision);
            assertEquals(CaptionConnection.PRIMARY, old.preferences.load().preferences.global.connectionId);
            assertEquals(0, next.connection.load().revision);
            assertNull(next.preferences.load().preferences.global.connectionId);
        } finally { finish.countDown(); io.shutdownNow(); }
    }
    @Test public void connectionCommitFailureCanRecoverAndRetryWithoutLosingExpectedRevision() {
        try (Stores stores = new Stores()) {
            Faults faults = new Faults();
            ReflectionHelpers.setField(stores.connection, "mPreferences", faults.preferences);
            CaptionConnectionStore.Snapshot expected = stores.connection.load();
            faults.fail.set(true);
            CaptionSettingsController.ConnectionWrite first = write(stores, expected, () -> true);
            assertFalse(first.result.saved);
            assertFalse(first.result.partial);
            assertEquals(expected.revision, first.result.snapshot.revision);
            assertFalse(first.result.snapshot.valid);
            faults.fail.set(false);
            // The real UI replaces expected with the failed acknowledgement snapshot, not the older valid one.
            CaptionSettingsController.ConnectionWrite retry = write(stores, first.result.snapshot, () -> true);
            assertTrue("The first retry after recovery must accept the UI's failed snapshot at the same revision", retry.result.saved);
            assertEquals(expected.revision + 1, retry.result.snapshot.revision);
        }
    }
    @Test public void partialPreferenceFailureReturnsNewRevisionAndBlocksBridgeUntilExplicitRetry() throws Exception {
        try (Stores stores = new Stores()) {
            Faults faults = new Faults();
            ReflectionHelpers.setField(stores.preferences, "mPreferences", faults.preferences);
            CaptionConnectionStore.Snapshot expected = stores.connection.load();
            faults.fail.set(true);
            CaptionSettingsController.ConnectionWrite first = write(stores, expected, () -> true);
            assertFalse(first.result.saved);
            assertTrue(first.result.partial);
            assertEquals("PARTIAL", first.result.code);
            assertEquals(expected.revision + 1, first.result.snapshot.revision);
            assertFalse(CaptionSettingsController.connectionUsable(true, 0));
            assertFalse(CaptionSettingsController.connectionUsable(false, 1));
            assertFalse(CaptionPairingDialog.saveReply(first.result).saved);
            org.json.JSONObject reply = new org.json.JSONObject(CaptionPairingDialog.saveReply(first.result).json);
            assertTrue(reply.getBoolean("partial"));
            assertEquals(Long.toString(first.result.snapshot.revision), reply.getJSONObject("state").getString("revision"));
            assertFalse(reply.toString().contains(KEY));
            faults.fail.set(false);
            CaptionSettingsController.ConnectionWrite retry = CaptionSettingsController.writeConnection(stores.connection, stores.preferences,
                    first.result.snapshot, first.result.snapshot.connection, CaptionConnectionStore.KeyEdit.KEEP, () -> null, false, () -> true);
            assertTrue(retry.result.saved);
            assertFalse(retry.result.partial);
            assertEquals("KEEP does not manufacture another revision", first.result.snapshot.revision, retry.result.snapshot.revision);
            assertTrue(CaptionSettingsController.connectionUsable(false, 0));
        }
    }
    @Test public void staleRevisionIsRejectedBeforeAnotherConnectionWrite() {
        try (Stores stores = new Stores()) {
            CaptionConnectionStore.Snapshot stale = stores.connection.load();
            assertTrue(write(stores, stale, () -> true).result.saved);
            CaptionSettingsController.ConnectionWrite rejected = write(stores, stale, () -> true);
            assertFalse(rejected.result.saved);
            assertEquals("REVISION_CONFLICT", rejected.result.code);
            assertEquals(1, rejected.result.snapshot.revision);
            assertEquals(1, stores.connection.load().revision);
        }
    }
    @Test public void clearWholeConnectionDiffersFromClearingOnlyTheKeyAndPreservesGlobalFields() {
        try (Stores stores = new Stores()) {
            CaptionSettingsController.ConnectionWrite initial = write(stores, stores.connection.load(), () -> true);
            CaptionPreferences.Global before = stores.preferences.load().preferences.global;
            CaptionSettingsController.ConnectionWrite keyOnly = CaptionSettingsController.writeConnection(stores.connection, stores.preferences,
                    initial.result.snapshot, config(), CaptionConnectionStore.KeyEdit.CLEAR, () -> null, false, () -> true);
            assertTrue(keyOnly.result.saved);
            assertNotNull(keyOnly.result.snapshot.connection);
            assertFalse(keyOnly.result.snapshot.hasKey);
            CaptionSettingsController.ConnectionWrite clear = CaptionSettingsController.writeConnection(stores.connection, stores.preferences,
                    keyOnly.result.snapshot, null, null, () -> null, true, () -> true);
            assertTrue(clear.result.saved);
            assertNull(clear.result.snapshot.connection);
            assertFalse(clear.result.snapshot.hasKey);
            assertEquals(before.targetLanguage, clear.preferences.preferences.global.targetLanguage);
            assertEquals(before.mode, clear.preferences.preferences.global.mode);
            assertEquals(before.order, clear.preferences.preferences.global.order);
        }
    }
    private static CaptionSettingsController.ConnectionWrite write(Stores stores, CaptionConnectionStore.Snapshot expected,
            CaptionSettingsController.WriteAllowed allowed) {
        return CaptionSettingsController.writeConnection(stores.connection, stores.preferences, expected, config(),
                CaptionConnectionStore.KeyEdit.REPLACE, () -> KEY, false, allowed);
    }
    private static CaptionConnection config() {
        return CaptionConnection.basic("https://fixture.invalid/chat", "fixture-model", 1024, ChatCompletionAdapter.Thinking.OMIT);
    }
    private static final class Stores implements AutoCloseable {
        final CaptionConnectionStore connection;
        final CaptionPreferenceStore preferences;
        Stores() {
            Context context = RuntimeEnvironment.getApplication(); String scope = "pairing-write-" + UUID.randomUUID();
            connection = new CaptionConnectionStore(context, scope);
            preferences = new CaptionPreferenceStore(context, scope, new CaptionPreferenceStore.Limits(32, 65536));
        }
        @Override public void close() { connection.close(); preferences.close(); }
    }
    private static final class Faults {
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicInteger commits = new AtomicInteger();
        final SharedPreferences preferences;
        Faults() {
            SharedPreferences backing = RuntimeEnvironment.getApplication().getSharedPreferences("pairing-fault-" + UUID.randomUUID(), Context.MODE_PRIVATE);
            preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return invoke(method, backing, args);
                        SharedPreferences.Editor editor = backing.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                                (self, editMethod, editArgs) -> {
                                    if (editMethod.getName().equals("commit")) {
                                        commits.incrementAndGet();
                                        boolean committed = editor.commit(); // Android can change memory even when persistence reports failure.
                                        return !fail.get() && committed;
                                    }
                                    Object result = invoke(editMethod, editor, editArgs);
                                    return result instanceof SharedPreferences.Editor ? self : result;
                                });
                    });
        }
    }
    private static Object invoke(java.lang.reflect.Method method, Object receiver, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }
}
