package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.content.Context;
import android.content.SharedPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.Assert.*;

/** Synthetic fixtures only: no real provider, Key, AndroidKeyStore, device, or network access. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionConnectionStoreTest {
    private static final String DATA = "connection";
    private static final String NAMESPACE = "synthetic-caption-connection-owner";
    private static final String ENDPOINT = "https://synthetic.invalid/v1/chat/completions";
    private static final String OTHER_ENDPOINT = "https://other.synthetic.invalid/v1/chat/completions";
    private static final String KEY_A = "synthetic-fixture-key-a-not-a-real-credential";
    private static final String KEY_B = "synthetic-fixture-key-b-not-a-real-credential";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final AtomicInteger PREFERENCES_ID = new AtomicInteger();
    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void freshStoreDoesNotWriteOrInventAConnectionOrKey() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore.Snapshot empty = store(preferences, cipher).load();
        assertTrue(empty.valid);
        assertEquals(0, empty.revision);
        assertNull(empty.connection);
        assertFalse(empty.hasKey);
        assertFalse(empty.persistentKey);
        assertNull(empty.profile("zh"));
        assertTrue(preferences.getAll().isEmpty());
        assertEquals(0, cipher.seals);
        assertEquals(0, cipher.opens);
    }

    @Test
    public void connectionRetainsExplicitBudgetsAndRejectsInvalidCallCounts() {
        CaptionConnection connection = CaptionConnection.basic(ENDPOINT, "synthetic-model", 2048,
                ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED);
        assertEquals(ENDPOINT, connection.wire.endpoint.toString());
        assertEquals("synthetic-model", connection.wire.model);
        assertEquals(2048, connection.wire.maxOutputTokens);
        assertEquals(262144, connection.wire.requestBytes);
        assertEquals(262144, connection.wire.responseBytes);
        assertEquals(ChatCompletionAdapter.TokenField.MAX_TOKENS, connection.wire.tokenField);
        assertEquals(ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED, connection.wire.thinking);
        assertEquals(12, connection.batch.items);
        assertEquals(6000, connection.batch.sourceCodePoints);
        assertEquals(2000, connection.batch.contextCodePoints);
        assertEquals(12000, connection.batch.translatedCodePoints);
        assertEquals(2, connection.maxCalls);
        invalid(() -> new CaptionConnection(null, connection.batch, 1));
        invalid(() -> new CaptionConnection(connection.wire, null, 1));
        invalid(() -> new CaptionConnection(connection.wire, connection.batch, 0));
        invalid(() -> new CaptionConnection(connection.wire, connection.batch, 9));
        assertEquals(1, new CaptionConnection(connection.wire, connection.batch, 1).maxCalls);
        assertEquals(8, new CaptionConnection(connection.wire, connection.batch, 8).maxCalls);
    }

    @Test
    public void freshCopiedPreferencesActuallyDecryptAesGcmAndRoundTripEveryConfigField() throws Exception {
        SharedPreferences original = freshPreferences();
        JceCipher firstCipher = new JceCipher();
        CaptionConnectionStore first = store(original, firstCipher);
        CaptionConnection config = detailedConnection();
        CaptionConnectionStore.Snapshot saved = first.save(first.load(), config, CaptionConnectionStore.KeyEdit.REPLACE, KEY_A);
        assertNotNull(saved);
        assertTrue(saved.hasKey);
        assertTrue(saved.persistentKey);
        assertEquals(1, firstCipher.seals);
        assertEquals(0, firstCipher.opens);
        assertNoPlaintext(original);
        String encrypted = json(original).getString("secret");
        assertTrue(encrypted.startsWith("1:"));
        assertNotEquals(KEY_A, encrypted);
        first.close();

        // A new preferences identity is essential: a new Store over the old object could just hit CACHE.
        SharedPreferences copied = copiedPreferences(original);
        JceCipher freshCipher = new JceCipher(firstCipher.key);
        CaptionConnectionStore.Snapshot restored = store(copied, freshCipher).load();
        assertTrue(restored.valid);
        assertTrue(restored.hasKey);
        assertTrue(restored.persistentKey);
        assertEquals(saved.revision, restored.revision);
        assertConnectionEquals(config, restored.connection);
        assertEquals(KEY_A, restored.profile("zh").credentials.getKey());
        assertEquals(1, freshCipher.opens);
        assertEquals(0, freshCipher.seals);
        assertEquals(raw(original), raw(copied));
        assertNoPlaintext(copied);
    }

    @Test
    public void unchangedKeepPreservesProfileIdentityAndDoesNotResealOrRewrite() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore owner = store(preferences, cipher);
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        TranslationCoordinator.Profile profile = saved.profile("fr");
        String before = raw(preferences);
        CaptionConnectionStore.Snapshot kept = owner.save(saved, connection(), CaptionConnectionStore.KeyEdit.KEEP, null);
        assertSame(saved, kept);
        assertSame(profile, kept.profile("fr"));
        assertEquals(before, raw(preferences));
        assertEquals(1, cipher.seals);
    }

    @Test
    public void temporarySealFailureCannotDowngradeAnExistingPersistentKeyOnKeep() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        CaptionSecretCipher toggled = new CaptionSecretCipher() {
            public String seal(String key, String binding) throws GeneralSecurityException {
                if (fail.get()) throw new GeneralSecurityException();
                return cipher.seal(key, binding);
            }
            public String open(String value, String binding) throws GeneralSecurityException { return cipher.open(value, binding); }
        };
        CaptionConnectionStore owner = store(preferences, toggled);
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        TranslationCoordinator.Profile profile = saved.profile("fr");
        String before = raw(preferences);
        fail.set(true);
        assertNull(owner.save(saved, connection(ENDPOINT, "synthetic-new-model"), CaptionConnectionStore.KeyEdit.KEEP, null));
        assertEquals(before, raw(preferences));
        assertEquals(KEY_A, profile.credentials.getKey());
        assertTrue(owner.load().persistentKey);
    }

    @Test
    public void snapshotPublicFieldsExposeOnlyConfigurationRevisionAndKeyStatus() throws Exception {
        SharedPreferences preferences = freshPreferences();
        CaptionConnectionStore owner = store(preferences, new JceCipher());
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        Set<String> fields = new HashSet<>();
        for (Field field : CaptionConnectionStore.Snapshot.class.getFields()) {
            fields.add(field.getName());
            assertNotEquals(String.class, field.getType());
            assertFalse(ChatTranslationClient.Credentials.class.isAssignableFrom(field.getType()));
            assertNotEquals(KEY_A, field.get(saved));
        }
        assertEquals(new HashSet<>(Arrays.asList("connection", "revision", "valid", "hasKey", "persistentKey")), fields);
        assertNoPlaintext(preferences);
    }

    @Test
    public void keepReplaceAndClearAreExplicitAndEachSuccessfulSaveRevokesOldCredentials() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore owner = store(preferences, cipher);
        CaptionConnectionStore.Snapshot first = saveKey(owner, KEY_A);
        TranslationCoordinator.Profile firstProfile = first.profile("zh");
        String firstEncrypted = json(preferences).getString("secret");

        CaptionConnection changedModel = connection(ENDPOINT, "synthetic-new-model");
        CaptionConnectionStore.Snapshot kept = owner.save(first, changedModel, CaptionConnectionStore.KeyEdit.KEEP, null);
        assertNotNull(kept);
        assertEquals(first.revision + 1, kept.revision);
        assertEquals(KEY_A, kept.profile("zh").credentials.getKey());
        assertNull(firstProfile.credentials.getKey());
        assertNull(first.profile("zh"));
        String keptEncrypted = json(preferences).getString("secret");
        assertNotEquals(firstEncrypted, keptEncrypted);
        assertNotEquals(firstEncrypted.split(":")[1], keptEncrypted.split(":")[1]);
        TranslationCoordinator.Profile keptProfile = kept.profile("zh");

        CaptionConnectionStore.Snapshot replaced = owner.save(kept, changedModel, CaptionConnectionStore.KeyEdit.REPLACE, KEY_B);
        assertNotNull(replaced);
        assertEquals(kept.revision + 1, replaced.revision);
        assertNull(keptProfile.credentials.getKey());
        assertEquals(KEY_B, replaced.profile("zh").credentials.getKey());
        TranslationCoordinator.Profile replacedProfile = replaced.profile("zh");

        CaptionConnectionStore.Snapshot cleared = owner.save(replaced, changedModel, CaptionConnectionStore.KeyEdit.CLEAR, null);
        assertNotNull(cleared);
        assertEquals(replaced.revision + 1, cleared.revision);
        assertSame(changedModel, cleared.connection);
        assertFalse(cleared.hasKey);
        assertFalse(cleared.persistentKey);
        assertNull(cleared.profile("zh"));
        assertNull(replacedProfile.credentials.getKey());
        assertTrue(json(preferences).isNull("secret"));
        assertNoPlaintext(preferences);
        CaptionConnectionStore.Snapshot restored = store(copiedPreferences(preferences), new JceCipher(cipher.key)).load();
        assertTrue(restored.valid);
        assertFalse(restored.hasKey);
        assertConnectionEquals(changedModel, restored.connection);
    }

    @Test
    public void differentEndpointCannotKeepAKeyButExplicitReplacementOrClearCanChangeEndpoint() throws Exception {
        SharedPreferences preferences = freshPreferences();
        CaptionConnectionStore owner = store(preferences, new JceCipher());
        CaptionConnectionStore.Snapshot first = saveKey(owner, KEY_A);
        TranslationCoordinator.Profile old = first.profile("zh");
        String before = raw(preferences);
        for (String endpoint : new String[]{OTHER_ENDPOINT, ENDPOINT + "/other"}) {
            assertNull(owner.save(first, connection(endpoint, "synthetic-model"), CaptionConnectionStore.KeyEdit.KEEP, null));
            assertEquals(before, raw(preferences));
            assertEquals(KEY_A, old.credentials.getKey());
        }
        CaptionConnectionStore.Snapshot replaced = owner.save(first, connection(OTHER_ENDPOINT, "synthetic-model"),
                CaptionConnectionStore.KeyEdit.REPLACE, KEY_B);
        assertNotNull(replaced);
        assertNull(old.credentials.getKey());
        assertEquals(KEY_B, replaced.profile("zh").credentials.getKey());
        CaptionConnectionStore.Snapshot cleared = owner.save(replaced, connection(ENDPOINT, "synthetic-model"),
                CaptionConnectionStore.KeyEdit.CLEAR, null);
        assertNotNull(cleared);
        assertFalse(cleared.hasKey);
        // With no secret to leak, KEEP can update an endpoint without manufacturing credentials.
        CaptionConnectionStore.Snapshot noKey = owner.save(cleared, connection(OTHER_ENDPOINT, "synthetic-model"),
                CaptionConnectionStore.KeyEdit.KEEP, null);
        assertNotNull(noKey);
        assertFalse(noKey.hasKey);
    }

    @Test
    public void invalidKeyEditsAreRejectedWithoutChangingStoredOrLiveCredentials() throws Exception {
        SharedPreferences preferences = freshPreferences();
        CaptionConnectionStore owner = store(preferences, new JceCipher());
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        String before = raw(preferences);
        for (String key : new String[]{null, "", "has space", "line\nbreak", "\t", "non-ascii-密钥", repeat('x', 8193)}) {
            invalid(() -> owner.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.REPLACE, key));
        }
        invalid(() -> owner.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.KEEP, KEY_B));
        invalid(() -> owner.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.CLEAR, KEY_B));
        invalid(() -> owner.save(saved, saved.connection, null, null));
        invalid(() -> owner.save(saved, null, CaptionConnectionStore.KeyEdit.KEEP, null));
        assertEquals(before, raw(preferences));
        assertEquals(KEY_A, saved.profile("zh").credentials.getKey());
    }

    @Test
    public void presentationOnlyChangesReuseProfileButTargetChangesGetAnIsolatedIdentity() throws Exception {
        SharedPreferences preferences = freshPreferences();
        CaptionConnectionStore owner = store(preferences, new JceCipher());
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        CaptionPreferences.Global original = new CaptionPreferences.Global("zh", NativeCueComposer.Mode.BILINGUAL,
                NativeCueComposer.Order.ORIGINAL_FIRST, CaptionConnection.PRIMARY);
        CaptionPreferences.Global displayOnly = new CaptionPreferences.Global("zh", NativeCueComposer.Mode.TRANSLATION,
                NativeCueComposer.Order.TRANSLATION_FIRST, CaptionConnection.PRIMARY);
        assertTrue(original.hasSameRequestSettings(displayOnly));
        assertFalse(original.hasSamePresentation(displayOnly));
        TranslationCoordinator.Profile profile = saved.profile(original.targetLanguage);
        String before = raw(preferences);
        assertSame(profile, saved.profile(displayOnly.targetLanguage));
        assertSame(profile, saved.profile(new String("zh")));
        assertSame(saved.connection.wire, profile.wire);
        assertSame(saved.connection.batch, profile.batchLimits);
        assertEquals(saved.connection.maxCalls, profile.maxCalls);
        assertEquals(saved.revision, profile.credentialRevision);
        assertNull(profile.sourceLanguage);
        assertEquals("", profile.context);
        assertNull(saved.profile(null));
        assertNull(saved.profile("not a language"));
        assertSame(profile, saved.profile("zh"));

        TranslationCoordinator.Profile french = saved.profile("fr");
        assertNotSame(profile, french);
        assertEquals("zh", profile.targetLanguage);
        assertEquals("fr", french.targetLanguage);
        assertSame(french, saved.profile("fr"));
        assertSame(profile.credentials, french.credentials);
        assertNotSame(french, saved.profile("zh"));
        assertEquals(before, raw(preferences));
    }

    @Test
    public void actualStoreTargetRoundTripReusesCompletedStringWithoutRevivingOldFrames() throws Exception {
        CaptionConnectionStore owner = store(freshPreferences(), new JceCipher());
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        try (SemanticPlayback playback = new SemanticPlayback()) {
            TranslationCoordinator.Profile zh = saved.profile("zh");
            playback.select(zh);
            playback.drain();
            TranslationCoordinator.Frame firstZh = playback.engine.prepareFrame();
            assertEquals("zh:source", firstZh.items.get(0).translation);
            assertEquals(1, playback.calls.get());

            TranslationCoordinator.Profile ja = saved.profile("ja");
            assertSame(zh.credentials, ja.credentials);
            playback.select(ja);
            assertFalse(playback.engine.deliver(firstZh, ignored -> fail()));
            assertNull(playback.engine.prepareFrame().items.get(0).translation);
            playback.drain();
            assertEquals("ja:source", playback.engine.prepareFrame().items.get(0).translation);
            assertEquals(2, playback.calls.get());

            TranslationCoordinator.Profile backToZh = saved.profile("zh");
            assertSame(zh.credentials, backToZh.credentials);
            assertEquals(zh.credentialRevision, backToZh.credentialRevision);
            playback.select(backToZh); // Real Store resolution, not manually reusing the first Profile.
            playback.drain();
            TranslationCoordinator.Frame currentZh = playback.engine.prepareFrame();
            assertEquals("zh:source", currentZh.items.get(0).translation);
            assertNotSame(firstZh, currentZh);
            assertFalse(playback.engine.deliver(firstZh, ignored -> fail("Cache reuse cannot revive old Frames")));
            assertTrue(playback.engine.deliver(currentZh, ignored -> { }));
            assertEquals("zh -> ja -> zh must not pay for a third translation", 2, playback.calls.get());
        } finally { owner.close(); }
    }

    @Test
    public void reloadingTheSameStoreStateDoesNotInventAnotherCompletedCacheNamespace() throws Exception {
        CaptionConnectionStore owner = store(freshPreferences(), new JceCipher());
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        try (SemanticPlayback playback = new SemanticPlayback()) {
            TranslationCoordinator.Profile first = saved.profile("zh");
            playback.select(first);
            playback.drain();
            TranslationCoordinator.Profile reloaded = owner.load().profile("zh");
            assertSame(first.credentials, reloaded.credentials);
            assertEquals(first.credentialRevision, reloaded.credentialRevision);
            playback.select(reloaded);
            playback.drain();
            assertEquals("zh:source", playback.engine.prepareFrame().items.get(0).translation);
            assertEquals("A fresh Store Snapshot is not a new credential/account state", 1, playback.calls.get());
        } finally { owner.close(); }
    }

    @Test
    public void equalRevisionStoreStatesRemainIsolatedForBothDifferentAccountsAndKeys() throws Exception {
        for (boolean sameKey : new boolean[]{true, false}) {
            CaptionConnectionStore firstOwner = store(freshPreferences(), new JceCipher());
            CaptionConnectionStore otherOwner = store(freshPreferences(), new JceCipher());
            CaptionConnectionStore.Snapshot first = saveKey(firstOwner, KEY_A);
            CaptionConnectionStore.Snapshot other = saveKey(otherOwner, sameKey ? KEY_A : KEY_B);
            assertEquals(first.revision, other.revision);
            assertConnectionEquals(first.connection, other.connection);
            try (SemanticPlayback playback = new SemanticPlayback()) {
                playback.select(first.profile("zh"));
                playback.drain();
                TranslationCoordinator.Frame old = playback.engine.prepareFrame();
                playback.select(other.profile("zh"));
                assertFalse(playback.engine.deliver(old, ignored -> fail()));
                assertNull("Revision and even equal secret bytes do not identify an account state",
                        playback.engine.prepareFrame().items.get(0).translation);
                playback.drain();
                assertEquals(2, playback.calls.get());
                assertEquals("zh:source", playback.engine.prepareFrame().items.get(0).translation);
            } finally { firstOwner.close(); otherOwner.close(); }
        }
    }

    @Test
    public void actualModelAndKeyUpdatesCannotReadThePreviousCompletedNamespace() throws Exception {
        CaptionConnectionStore owner = store(freshPreferences(), new JceCipher());
        CaptionConnectionStore.Snapshot initial = saveKey(owner, KEY_A);
        try (SemanticPlayback playback = new SemanticPlayback()) {
            playback.select(initial.profile("zh"));
            playback.drain();
            CaptionConnectionStore.Snapshot modelChange = owner.save(initial,
                    connection(ENDPOINT, "synthetic-other-model"), CaptionConnectionStore.KeyEdit.KEEP, null);
            assertNotNull(modelChange);
            playback.select(modelChange.profile("zh"));
            assertNull(playback.engine.prepareFrame().items.get(0).translation);
            playback.drain();
            assertEquals(2, playback.calls.get());
            CaptionConnectionStore.Snapshot keyChange = owner.save(modelChange, modelChange.connection,
                    CaptionConnectionStore.KeyEdit.REPLACE, KEY_B);
            assertNotNull(keyChange);
            playback.select(keyChange.profile("zh"));
            assertNull(playback.engine.prepareFrame().items.get(0).translation);
            playback.drain();
            assertEquals(3, playback.calls.get());
            assertEquals("zh:source", playback.engine.prepareFrame().items.get(0).translation);
        } finally { owner.close(); }
    }

    @Test
    public void anotherStoreSuccessfulSaveRevokesAllOldTargetProfiles() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore first = store(preferences, cipher);
        CaptionConnectionStore.Snapshot saved = saveKey(first, KEY_A);
        TranslationCoordinator.Profile zh = saved.profile("zh"), fr = saved.profile("fr");
        CaptionConnectionStore second = store(preferences, new JceCipher(cipher.key));
        CaptionConnectionStore.Snapshot next = second.save(second.load(), saved.connection, CaptionConnectionStore.KeyEdit.REPLACE, KEY_B);
        assertNotNull(next);
        assertNull(zh.credentials.getKey());
        assertNull(fr.credentials.getKey());
        assertNull(saved.profile("zh"));
        assertEquals(KEY_B, first.load().profile("zh").credentials.getKey());
        assertNull(first.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.KEEP, null));
    }

    @Test
    public void decryptionFailureKeepsEncryptedDataAndRequiresExplicitReplaceOrClear() throws Exception {
        SharedPreferences original = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore writer = store(original, cipher);
        saveKey(writer, KEY_A);
        for (boolean runtime : new boolean[]{false, true}) {
            SharedPreferences copied = copiedPreferences(original);
            CaptionConnectionStore reader = store(copied, new UnavailableCipher(runtime));
            CaptionConnectionStore.Snapshot unavailable = reader.load();
            assertTrue(unavailable.valid);
            assertNotNull(unavailable.connection);
            assertFalse(unavailable.hasKey);
            assertFalse(unavailable.persistentKey);
            assertNull(unavailable.profile("zh"));
            String before = raw(copied);
            assertNull(reader.save(unavailable, unavailable.connection, CaptionConnectionStore.KeyEdit.KEEP, null));
            assertEquals(before, raw(copied));
            assertNotNull(json(copied).getString("secret"));
            CaptionConnectionStore.Snapshot cleared = reader.save(unavailable, unavailable.connection, CaptionConnectionStore.KeyEdit.CLEAR, null);
            assertNotNull(cleared);
            assertTrue(json(copied).isNull("secret"));
        }
        // A different AES key exercises real GCM authentication failure, not just a throwing stub.
        SharedPreferences copied = copiedPreferences(original);
        CaptionConnectionStore reader = store(copied, new JceCipher());
        CaptionConnectionStore.Snapshot unavailable = reader.load();
        assertTrue(unavailable.valid);
        assertFalse(unavailable.hasKey);
        assertNull(reader.save(unavailable, unavailable.connection, CaptionConnectionStore.KeyEdit.KEEP, null));
        assertEquals(raw(original), raw(copied));
        CaptionConnectionStore.Snapshot replaced = reader.save(unavailable, unavailable.connection, CaptionConnectionStore.KeyEdit.REPLACE, KEY_B);
        assertNotNull(replaced);
        assertTrue(replaced.persistentKey);
        assertEquals(KEY_B, replaced.profile("zh").credentials.getKey());
    }

    @Test
    public void aesGcmAadRejectsConfigRevisionNamespaceAndCiphertextTampering() throws Exception {
        SharedPreferences original = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore writer = store(original, cipher);
        saveKey(writer, KEY_A);
        for (String mutation : new String[]{"config", "revision", "namespace", "ciphertext"}) {
            SharedPreferences copied = copiedPreferences(original);
            JSONObject altered = json(copied);
            if (mutation.equals("config")) altered.getJSONObject("config").put("model", "synthetic-tampered-model");
            if (mutation.equals("revision")) altered.put("revision", "2");
            if (mutation.equals("ciphertext")) {
                String[] parts = altered.getString("secret").split(":", -1);
                byte[] bytes = Base64.getDecoder().decode(parts[2]);
                bytes[0] ^= 1;
                altered.put("secret", parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(bytes));
            }
            assertTrue(copied.edit().putString(DATA, altered.toString()).commit());
            JceCipher freshCipher = new JceCipher(cipher.key);
            CaptionConnectionStore reader = new CaptionConnectionStore(copied, freshCipher,
                    mutation.equals("namespace") ? "synthetic-other-owner" : NAMESPACE);
            CaptionConnectionStore.Snapshot unavailable = reader.load();
            assertTrue(mutation, unavailable.valid);
            assertFalse(mutation, unavailable.hasKey);
            assertNull(unavailable.profile("zh"));
            assertEquals(1, freshCipher.opens);
            String before = raw(copied);
            assertNull(reader.save(unavailable, unavailable.connection, CaptionConnectionStore.KeyEdit.KEEP, null));
            assertEquals(before, raw(copied));
        }
    }

    @Test
    public void encryptionFailureStoresOnlyConfigWhileKeySurvivesOnlyInTheCurrentProcess() throws Exception {
        for (boolean runtime : new boolean[]{false, true}) {
            SharedPreferences original = freshPreferences();
            CaptionConnectionStore writer = store(original, new UnavailableCipher(runtime));
            CaptionConnectionStore.Snapshot saved = saveKey(writer, KEY_A);
            assertTrue(saved.hasKey);
            assertFalse(saved.persistentKey);
            assertEquals(KEY_A, saved.profile("zh").credentials.getKey());
            assertTrue(json(original).isNull("secret"));
            assertNoPlaintext(original);
            // Same-process Store instances may retain the memory-only credential through last-good.
            assertEquals(KEY_A, store(original, new UnavailableCipher(runtime)).load().profile("zh").credentials.getKey());
            CaptionConnectionStore.Snapshot kept = writer.save(saved, connection(ENDPOINT, "synthetic-edited-model"),
                    CaptionConnectionStore.KeyEdit.KEEP, null);
            assertNotNull(kept);
            assertEquals(KEY_A, kept.profile("zh").credentials.getKey());
            assertFalse(kept.persistentKey);
            writer.close();

            SharedPreferences copied = copiedPreferences(original);
            CaptionConnectionStore.Snapshot restarted = store(copied, new UnavailableCipher(runtime)).load();
            assertTrue(restarted.valid);
            assertConnectionEquals(kept.connection, restarted.connection);
            assertEquals(kept.revision, restarted.revision);
            assertFalse(restarted.hasKey);
            assertFalse(restarted.persistentKey);
            assertNull(restarted.profile("zh"));
            assertNoPlaintext(copied);
        }
    }

    @Test
    public void unsupportedSdkBranchRefusesSecretsInsteadOfUsingPlaintextFallback() throws Exception {
        // Exercise the version branch under the cached SDK27 runtime, not an Android22 device claim.
        int sdk = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 22);
            CaptionSecretCipher cipher = CaptionSecretCipher.create(NAMESPACE);
            rejectsSecret(() -> cipher.seal(KEY_A, "synthetic-aad"));
            rejectsSecret(() -> cipher.open("synthetic-not-encrypted", "synthetic-aad"));
            SharedPreferences preferences = freshPreferences();
            CaptionConnectionStore owner = store(preferences, cipher);
            CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
            assertTrue(saved.hasKey);
            assertFalse(saved.persistentKey);
            assertTrue(json(preferences).isNull("secret"));
            assertNoPlaintext(preferences);
        } finally { org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk); }
    }

    @Test
    public void api23CipherRejectsMalformedEnvelopesBeforeNeedingAndroidKeyStore() throws Exception {
        CaptionSecretCipher cipher = new CaptionSecretCipher.Api23(NAMESPACE);
        String iv = Base64.getEncoder().encodeToString(new byte[12]);
        String tag = Base64.getEncoder().encodeToString(new byte[16]);
        for (String encrypted : new String[]{null, "", "synthetic-plaintext", "2:" + iv + ":" + tag,
                "1:" + iv + ":" + tag + ":extra", "1:AA==:" + tag, "1:" + iv + ":AA==", repeat('x', 32769)}) {
            rejectsSecret(() -> cipher.open(encrypted, "synthetic-aad"));
        }
    }

    @Test
    public void clearWritesATombstoneSoOldEmptyAndAbaSnapshotsCannotWriteAgain() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore owner = store(preferences, cipher);
        CaptionConnectionStore.Snapshot initial = owner.load();
        CaptionConnectionStore.Snapshot saved = saveKey(owner, KEY_A);
        TranslationCoordinator.Profile oldProfile = saved.profile("zh");
        CaptionConnectionStore.Snapshot cleared = owner.clear(saved);
        assertNotNull(cleared);
        assertTrue(cleared.valid);
        assertNull(cleared.connection);
        assertFalse(cleared.hasKey);
        assertEquals(saved.revision + 1, cleared.revision);
        assertTrue(preferences.contains(DATA));
        assertTrue(json(preferences).isNull("config"));
        assertTrue(json(preferences).isNull("secret"));
        assertEquals(Long.toString(cleared.revision), json(preferences).getString("revision"));
        assertNull(oldProfile.credentials.getKey());
        assertNull(owner.save(initial, saved.connection, CaptionConnectionStore.KeyEdit.REPLACE, KEY_B));
        assertNull(owner.clear(initial));
        assertNull(owner.clear(saved));

        CaptionConnectionStore.Snapshot recreated = owner.save(cleared, saved.connection, CaptionConnectionStore.KeyEdit.REPLACE, KEY_A);
        assertNotNull(recreated);
        assertEquals(cleared.revision + 1, recreated.revision);
        assertConnectionEquals(saved.connection, recreated.connection);
        assertNull(owner.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.KEEP, null));
        assertNull(owner.clear(cleared));
        CaptionConnectionStore.Snapshot finalClear = owner.clear(recreated);
        assertNotNull(finalClear);
        CaptionConnectionStore.Snapshot restored = store(copiedPreferences(preferences), new JceCipher(cipher.key)).load();
        assertTrue(restored.valid);
        assertNull(restored.connection);
        assertEquals(finalClear.revision, restored.revision);
    }

    @Test
    public void snapshotsAreBoundToTheirStoreEvenWhenPreferencesAndRevisionMatch() throws Exception {
        SharedPreferences preferences = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore first = store(preferences, cipher);
        CaptionConnectionStore second = store(preferences, new JceCipher(cipher.key));
        CaptionConnectionStore.Snapshot initial = first.load();
        assertNull(second.save(initial, connection(), CaptionConnectionStore.KeyEdit.REPLACE, KEY_A));
        assertNull(second.clear(initial));
        assertNull(first.save(null, connection(), CaptionConnectionStore.KeyEdit.KEEP, null));
        assertNull(first.clear(null));
        CaptionConnectionStore.Snapshot saved = saveKey(first, KEY_A);
        CaptionConnectionStore otherOwner = store(copiedPreferences(preferences), new JceCipher(cipher.key));
        assertNull(otherOwner.clear(saved));
        assertNull(otherOwner.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.REPLACE, KEY_B));
        first.close();
        assertNull(first.save(saved, saved.connection, CaptionConnectionStore.KeyEdit.KEEP, null));
        assertNull(first.clear(saved));
        assertNull(first.recoverFailedWrite());
        try { first.load(); fail("Closed store must not load"); }
        catch (IllegalStateException expected) { }
        assertEquals(KEY_A, second.load().profile("zh").credentials.getKey());
    }

    @Test
    public void corruptUnknownOrAmbiguousJsonIsPreservedUntilExplicitClear() throws Exception {
        SharedPreferences source = freshPreferences();
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore writer = store(source, cipher);
        saveKey(writer, KEY_A);
        String valid = raw(source);
        List<String> invalidData = new ArrayList<>(Arrays.asList("not-json", "[]", "{}",
                "{\"version\":1,\"version\":1}", repeat('x', 65537)));
        invalidData.add(new JSONObject(valid).put("version", 2).toString());
        invalidData.add(new JSONObject(valid).put("future", true).toString());
        invalidData.add(new JSONObject(valid).put("revision", "-1").toString());
        invalidData.add(new JSONObject(valid).put("revision", "9223372036854775808").toString());
        invalidData.add(new JSONObject(valid).put("revision", 1).toString());
        invalidData.add(new JSONObject(valid).put("config", JSONObject.NULL).toString());
        JSONObject extraConfig = new JSONObject(valid);
        extraConfig.getJSONObject("config").put("future", true);
        invalidData.add(extraConfig.toString());
        JSONObject badCalls = new JSONObject(valid);
        badCalls.getJSONObject("config").put("calls", 1.5);
        invalidData.add(badCalls.toString());
        JSONObject badEndpoint = new JSONObject(valid);
        badEndpoint.getJSONObject("config").put("endpoint", "http://synthetic.invalid/insecure");
        invalidData.add(badEndpoint.toString());
        JSONObject missing = new JSONObject(valid);
        missing.remove("secret");
        invalidData.add(missing.toString());

        for (String invalid : invalidData) {
            SharedPreferences preferences = freshPreferences();
            assertTrue(preferences.edit().putString(DATA, invalid).commit());
            CaptionConnectionStore owner = store(preferences, new JceCipher(cipher.key));
            CaptionConnectionStore.Snapshot loaded = owner.load();
            assertFalse(loaded.valid);
            assertNull(loaded.connection);
            assertFalse(loaded.hasKey);
            assertNull(loaded.profile("zh"));
            assertNull(owner.save(loaded, connection(), CaptionConnectionStore.KeyEdit.REPLACE, KEY_B));
            assertEquals(invalid, raw(preferences));
            assertNotNull(owner.clear(loaded));
            assertTrue(owner.load().valid);
            assertTrue(json(preferences).isNull("config"));
        }
    }

    @Test
    public void wrongPreferenceTypeCannotBeSilentlyOverwrittenEvenByClear() throws Exception {
        SharedPreferences preferences = freshPreferences();
        assertTrue(preferences.edit().putInt(DATA, 7).commit());
        CaptionConnectionStore owner = store(preferences, new JceCipher());
        CaptionConnectionStore.Snapshot loaded = owner.load();
        assertFalse(loaded.valid);
        assertNull(owner.save(loaded, connection(), CaptionConnectionStore.KeyEdit.REPLACE, KEY_A));
        assertNull(owner.clear(loaded));
        assertEquals(7, preferences.getInt(DATA, 0));
    }

    @Test
    public void maximumRevisionCannotWrapOnSaveOrClear() throws Exception {
        SharedPreferences preferences = freshPreferences();
        String tombstone = new JSONObject().put("version", 1).put("revision", Long.toString(Long.MAX_VALUE))
                .put("config", JSONObject.NULL).put("secret", JSONObject.NULL).toString();
        assertTrue(preferences.edit().putString(DATA, tombstone).commit());
        CaptionConnectionStore owner = store(preferences, new JceCipher());
        CaptionConnectionStore.Snapshot loaded = owner.load();
        assertTrue(loaded.valid);
        assertEquals(Long.MAX_VALUE, loaded.revision);
        assertNull(owner.save(loaded, connection(), CaptionConnectionStore.KeyEdit.REPLACE, KEY_A));
        assertNull(owner.clear(loaded));
        assertEquals(tombstone, raw(preferences));
    }

    @Test
    public void failedCommitAndFailedRollbackFreezeLastGoodAcrossStoresAndRecoveryOnlyRestoresOldRaw() throws Exception {
        SharedPreferences backing = freshPreferences();
        FaultingPreferences faults = new FaultingPreferences(backing);
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore first = store(faults.preferences, cipher);
        CaptionConnectionStore.Snapshot good = saveKey(first, KEY_A);
        TranslationCoordinator.Profile oldProfile = good.profile("zh");
        String oldRaw = raw(backing);
        CaptionConnectionStore second = store(faults.preferences, new JceCipher(cipher.key));
        CaptionConnectionStore.Snapshot peerBeforeFailure = second.load();
        faults.fail = true;
        assertNull(first.save(good, connection(OTHER_ENDPOINT, "synthetic-abandoned-model"),
                CaptionConnectionStore.KeyEdit.REPLACE, KEY_B));
        assertEquals(2, faults.failedCommits); // The failing write followed by the failing rollback.
        assertNotEquals(oldRaw, raw(backing));
        assertEquals(OTHER_ENDPOINT, json(backing).getJSONObject("config").getString("endpoint"));
        CaptionConnectionStore.Snapshot isolated = first.load(), peer = second.load();
        assertLastGood(isolated, good);
        assertLastGood(peer, good);
        assertEquals(KEY_A, oldProfile.credentials.getKey());
        assertNull(first.save(good, connection(), CaptionConnectionStore.KeyEdit.KEEP, null));
        assertNull(first.save(isolated, connection(), CaptionConnectionStore.KeyEdit.KEEP, null));
        assertNull(second.save(peerBeforeFailure, connection(), CaptionConnectionStore.KeyEdit.CLEAR, null));
        assertNull(second.clear(peer));
        assertNull(second.recoverFailedWrite());
        assertFalse(first.load().valid);
        assertNotEquals(oldRaw, raw(backing));
        assertNoPlaintext(backing);

        faults.fail = false;
        CaptionConnectionStore.Snapshot recovered = second.recoverFailedWrite();
        assertNotNull(recovered);
        assertTrue(recovered.valid);
        assertEquals(good.revision, recovered.revision);
        assertConnectionEquals(good.connection, recovered.connection);
        assertEquals(oldRaw, raw(backing));
        assertEquals(KEY_A, recovered.profile("zh").credentials.getKey());
        assertEquals(KEY_A, oldProfile.credentials.getKey());
        assertTrue(first.load().valid);
        CaptionConnectionStore.Snapshot saved = first.save(first.load(), connection(ENDPOINT, "synthetic-later-model"),
                CaptionConnectionStore.KeyEdit.KEEP, null);
        assertNotNull(saved);
        assertEquals(good.revision + 1, saved.revision);
        assertEquals(KEY_A, saved.profile("zh").credentials.getKey());
        assertEquals(ENDPOINT, saved.connection.wire.endpoint.toString());
        assertNull(oldProfile.credentials.getKey());
        CaptionConnectionStore.Snapshot restarted = store(copiedPreferences(backing), new JceCipher(cipher.key)).load();
        assertEquals(KEY_A, restarted.profile("zh").credentials.getKey());
        assertEquals("synthetic-later-model", restarted.connection.wire.model);
    }

    @Test
    public void failedFirstSaveRecoversAbsenceNotTheAbandonedConnectionAndPreservesUnrelatedPreferences() throws Exception {
        SharedPreferences backing = freshPreferences();
        assertTrue(backing.edit().putString("unrelated", "synthetic-other-setting").commit());
        FaultingPreferences faults = new FaultingPreferences(backing);
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore first = store(faults.preferences, cipher);
        faults.fail = true;
        assertNull(first.save(first.load(), connection(), CaptionConnectionStore.KeyEdit.REPLACE, KEY_B));
        assertTrue(backing.contains(DATA));
        CaptionConnectionStore second = store(faults.preferences, new JceCipher(cipher.key));
        CaptionConnectionStore.Snapshot isolated = second.load();
        assertFalse(isolated.valid);
        assertNull(isolated.connection);
        assertFalse(isolated.hasKey);
        assertEquals(0, isolated.revision);
        assertNull(second.recoverFailedWrite());
        faults.fail = false;
        CaptionConnectionStore.Snapshot recovered = second.recoverFailedWrite();
        assertNotNull(recovered);
        assertTrue(recovered.valid);
        assertNull(recovered.connection);
        assertFalse(recovered.hasKey);
        assertEquals(0, recovered.revision);
        assertFalse(backing.contains(DATA));
        assertEquals("synthetic-other-setting", backing.getString("unrelated", null));
        assertTrue(first.load().valid);
    }

    @Test
    public void failedClearCannotPublishItsInMemoryTombstoneOrRevokeLastGoodCredentials() throws Exception {
        SharedPreferences backing = freshPreferences();
        FaultingPreferences faults = new FaultingPreferences(backing);
        JceCipher cipher = new JceCipher();
        CaptionConnectionStore first = store(faults.preferences, cipher);
        CaptionConnectionStore.Snapshot good = saveKey(first, KEY_A);
        TranslationCoordinator.Profile oldProfile = good.profile("zh");
        String oldRaw = raw(backing);
        faults.fail = true;
        assertNull(first.clear(good));
        assertTrue(json(backing).isNull("config"));
        CaptionConnectionStore second = store(faults.preferences, new JceCipher(cipher.key));
        assertLastGood(second.load(), good);
        assertEquals(KEY_A, oldProfile.credentials.getKey());
        assertNull(first.clear(good));
        assertNull(second.recoverFailedWrite());
        faults.fail = false;
        CaptionConnectionStore.Snapshot recovered = second.recoverFailedWrite();
        assertNotNull(recovered);
        assertEquals(oldRaw, raw(backing));
        assertEquals(KEY_A, recovered.profile("zh").credentials.getKey());
        CaptionConnectionStore.Snapshot cleared = second.clear(recovered);
        assertNotNull(cleared);
        assertNull(oldProfile.credentials.getKey());
        assertEquals(good.revision + 1, cleared.revision);
        assertNull(cleared.connection);
        assertNull(first.load().connection);
    }

    /** Real Store Profiles and coordinator; the transport interceptor returns before DNS/network. */
    private static final class SemanticPlayback implements AutoCloseable {
        final CaptionSession session = new CaptionSession();
        final List<Runnable> tasks = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final TranslationCoordinator engine;

        SemanticPlayback() {
            RawTimeline raw = RawWebvttParser.parse("WEBVTT\n\n00:01.000 --> 00:05.000\nsource\n".getBytes(UTF8), 0,
                    new RawTimeline.Limits(4096, 8, 16));
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, 16);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(8, 16, 16));
            CaptionSession.Track track = session.selectTrack(0, null);
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan));
            engine = TranslationCoordinatorTest.uiFixture(session, () -> 0, tasks::add, calls,
                    new java.util.concurrent.atomic.AtomicReference<>(), false,
                    (target, source) -> target + ":" + source);
        }

        void select(TranslationCoordinator.Profile profile) {
            assertNotNull(profile);
            engine.configure(profile);
            engine.tick(1_000_000, false);
            engine.setVisible(true);
            engine.setRequesting(true);
        }

        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) {
                if (++count > 20) throw new AssertionError("Unbounded synthetic work");
                tasks.remove(0).run();
            }
        }

        public void close() { engine.close(); session.close(); }
    }

    private SharedPreferences freshPreferences() {
        return context.getSharedPreferences("synthetic-caption-connection-test-" + PREFERENCES_ID.incrementAndGet(), Context.MODE_PRIVATE);
    }

    private SharedPreferences copiedPreferences(SharedPreferences original) {
        SharedPreferences copied = freshPreferences();
        assertNotSame(original, copied);
        SharedPreferences.Editor editor = copied.edit();
        for (Map.Entry<String, ?> entry : original.getAll().entrySet()) {
            assertTrue("These synthetic restart fixtures contain strings only", entry.getValue() instanceof String);
            editor.putString(entry.getKey(), (String) entry.getValue());
        }
        assertTrue(editor.commit());
        return copied;
    }

    private static CaptionConnectionStore store(SharedPreferences preferences, CaptionSecretCipher cipher) {
        return new CaptionConnectionStore(preferences, cipher, NAMESPACE);
    }

    private static CaptionConnectionStore.Snapshot saveKey(CaptionConnectionStore owner, String key) {
        CaptionConnectionStore.Snapshot saved = owner.save(owner.load(), connection(), CaptionConnectionStore.KeyEdit.REPLACE, key);
        assertNotNull(saved);
        return saved;
    }

    private static CaptionConnection connection() { return connection(ENDPOINT, "synthetic-model"); }
    private static CaptionConnection connection(String endpoint, String model) {
        return CaptionConnection.basic(endpoint, model, 1024, ChatCompletionAdapter.Thinking.OMIT);
    }

    private static CaptionConnection detailedConnection() {
        return new CaptionConnection(new ChatCompletionAdapter.Config(ENDPOINT, "synthetic-model-detailed", 4096,
                180000, 190000, ChatCompletionAdapter.TokenField.MAX_COMPLETION_TOKENS,
                ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED), new TranslationBatch.Limits(7, 4000, 1500, 9000), 5);
    }

    private static void assertConnectionEquals(CaptionConnection expected, CaptionConnection actual) {
        assertNotNull(actual);
        assertEquals(expected.wire.endpoint, actual.wire.endpoint);
        assertEquals(expected.wire.model, actual.wire.model);
        assertEquals(expected.wire.maxOutputTokens, actual.wire.maxOutputTokens);
        assertEquals(expected.wire.requestBytes, actual.wire.requestBytes);
        assertEquals(expected.wire.responseBytes, actual.wire.responseBytes);
        assertEquals(expected.wire.tokenField, actual.wire.tokenField);
        assertEquals(expected.wire.thinking, actual.wire.thinking);
        assertEquals(expected.batch.items, actual.batch.items);
        assertEquals(expected.batch.sourceCodePoints, actual.batch.sourceCodePoints);
        assertEquals(expected.batch.contextCodePoints, actual.batch.contextCodePoints);
        assertEquals(expected.batch.translatedCodePoints, actual.batch.translatedCodePoints);
        assertEquals(expected.maxCalls, actual.maxCalls);
    }

    private static void assertLastGood(CaptionConnectionStore.Snapshot isolated, CaptionConnectionStore.Snapshot good) {
        assertFalse(isolated.valid);
        assertEquals(good.revision, isolated.revision);
        assertConnectionEquals(good.connection, isolated.connection);
        assertTrue(isolated.hasKey);
        assertTrue(isolated.persistentKey);
        assertNull(isolated.profile("zh"));
    }

    private static String raw(SharedPreferences preferences) { return preferences.getString(DATA, null); }
    private static JSONObject json(SharedPreferences preferences) throws Exception { return new JSONObject(raw(preferences)); }
    private static void assertNoPlaintext(SharedPreferences preferences) {
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            for (String secret : new String[]{KEY_A, KEY_B}) {
                assertFalse(entry.getKey().contains(secret));
                assertFalse(String.valueOf(entry.getValue()).contains(secret));
            }
        }
    }

    private static String repeat(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }

    private static void invalid(Runnable action) {
        try { action.run(); fail("Invalid synthetic configuration must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }

    private interface SecretAction { void run() throws GeneralSecurityException; }
    private static void rejectsSecret(SecretAction action) throws Exception {
        try { action.run(); fail("Synthetic secret operation must fail closed"); }
        catch (GeneralSecurityException expected) { }
    }

    /** Real authenticated encryption with an ephemeral JCE key; never asks Robolectric for AndroidKeyStore. */
    private static final class JceCipher implements CaptionSecretCipher {
        final SecretKey key;
        final SecureRandom random = new SecureRandom();
        int seals;
        int opens;
        JceCipher() throws GeneralSecurityException { this(newKey()); }
        JceCipher(SecretKey key) { this.key = key; }
        private static SecretKey newKey() throws GeneralSecurityException {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(128);
            return generator.generateKey();
        }
        public String seal(String secret, String binding) throws GeneralSecurityException {
            seals++;
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(binding.getBytes(UTF8));
            return "1:" + Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(cipher.doFinal(secret.getBytes(UTF8)));
        }
        public String open(String encrypted, String binding) throws GeneralSecurityException {
            opens++;
            try {
                String[] parts = encrypted.split(":", -1);
                if (parts.length != 3 || !"1".equals(parts[0])) throw new GeneralSecurityException();
                byte[] iv = Base64.getDecoder().decode(parts[1]);
                if (iv.length != 12) throw new GeneralSecurityException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                cipher.updateAAD(binding.getBytes(UTF8));
                return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), UTF8);
            } catch (IllegalArgumentException exception) { throw new GeneralSecurityException("Invalid synthetic envelope"); }
        }
    }

    private static final class UnavailableCipher implements CaptionSecretCipher {
        final boolean runtime;
        UnavailableCipher(boolean runtime) { this.runtime = runtime; }
        private void unavailable() throws GeneralSecurityException {
            if (runtime) throw new IllegalStateException("Synthetic cipher unavailable");
            throw new GeneralSecurityException("Synthetic cipher unavailable");
        }
        public String seal(String secret, String binding) throws GeneralSecurityException { unavailable(); return null; }
        public String open(String encrypted, String binding) throws GeneralSecurityException { unavailable(); return null; }
    }

    /** First failed commit changes memory; rollback and recovery attempts then throw before changing it. */
    private static final class FaultingPreferences {
        final SharedPreferences preferences;
        boolean fail;
        int failedCommits;
        FaultingPreferences(SharedPreferences backing) {
            preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if (method.getName().equals("equals")) return proxy == args[0];
                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (method.getName().equals("toString")) return "SyntheticFaultingPreferences";
                        }
                        if (!method.getName().equals("edit")) return invoke(method, backing, args);
                        SharedPreferences.Editor editor = backing.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                    if (editMethod.getName().equals("commit") && fail) {
                                        if (failedCommits++ > 0) throw new IllegalStateException("Synthetic rollback storage failure");
                                        editor.commit();
                                        return false;
                                    }
                                    Object value = invoke(editMethod, editor, editArgs);
                                    return value instanceof SharedPreferences.Editor ? editProxy : value;
                                });
                    });
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }
}
