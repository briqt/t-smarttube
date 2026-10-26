package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.content.Context;
import android.content.SharedPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionPreferenceStoreTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final CaptionPreferenceStore.Limits limits = new CaptionPreferenceStore.Limits(32, 65536);

    @Test
    public void defaultsDoNotWriteOrGuessATargetAndGlobalAndChannelChangesPersistSeparately() {
        CaptionPreferenceStore store = store("native-profile-a");
        CaptionPreferenceStore.Snapshot initial = store.load();
        assertTrue(initial.valid);
        assertFalse(raw("native-profile-a").contains("settings"));
        CaptionPreferenceStore.Snapshot changed = store.updateGlobal(initial, global("zh"));
        assertNotNull(changed);
        changed = store.updateChannel(changed, "channel-a", new CaptionPreferences.Channel(true, "en", CaptionSource.TextKind.ASR_CANDIDATE));
        assertNotNull(changed);
        store.close();
        CaptionPreferenceStore.Snapshot restored = store("native-profile-a").load();
        assertEquals("zh", restored.preferences.global.targetLanguage);
        assertTrue(restored.preferences.channel("channel-a").translate);
        assertEquals("en", restored.preferences.channel("channel-a").subtitleLanguage);
        assertFalse(restored.preferences.channel("channel-b").translate);
        assertEquals("connection-main", restored.preferences.global.connectionId);
    }

    @Test
    public void globalChangesApplyAcrossChannelsWithoutCopyingGlobalValuesIntoThem() throws Exception {
        CaptionPreferenceStore store = store("native-profile-a");
        CaptionPreferenceStore.Snapshot current = store.updateGlobal(store.load(), global("zh"));
        current = store.updateChannel(current, "channel-a", new CaptionPreferences.Channel(true, "en", CaptionSource.TextKind.UNKNOWN));
        current = store.updateChannel(current, "channel-b", new CaptionPreferences.Channel(false, "ja", CaptionSource.TextKind.UNKNOWN));
        current = store.updateGlobal(current, global("fr"));
        assertEquals("fr", current.preferences.global.targetLanguage);
        assertEquals("en", current.preferences.channel("channel-a").subtitleLanguage);
        assertEquals("ja", current.preferences.channel("channel-b").subtitleLanguage);
        JSONObject serialized = BoundedJson.object(raw("native-profile-a").getString("settings", null));
        assertEquals(3, serialized.getJSONObject("channels").getJSONObject("channel-a").length());
        assertFalse(serialized.toString().contains("api_key"));
        assertFalse(serialized.getJSONObject("channels").getJSONObject("channel-a").has("target"));
    }

    @Test
    public void storesAreBoundToExistingUserScopeAndForeignSnapshotsAreRejected() {
        CaptionPreferenceStore a = store("native-profile-a"), b = store("native-profile-b");
        CaptionPreferenceStore.Snapshot first = a.load();
        assertNull(b.updateGlobal(first, global("fr")));
        assertNotNull(a.updateGlobal(first, global("zh")));
        assertNull(b.load().preferences.global.targetLanguage);
        assertNotEquals(CaptionPreferenceStore.storageName("native-profile-a"), CaptionPreferenceStore.storageName("native-profile-b"));
        assertFalse(CaptionPreferenceStore.storageName("private user name").contains("private"));
        a.close();
        assertNull(a.updateGlobal(first, global("fr")));
    }

    @Test
    public void staleWritersAndReturnToSameValuesCannotOverwriteANewerRevision() {
        CaptionPreferenceStore a = store("native-profile-a"), b = store("native-profile-a");
        CaptionPreferenceStore.Snapshot initialA = a.load(), initialB = b.load();
        CaptionPreferenceStore.Snapshot old = a.updateGlobal(initialA, global("zh"));
        assertNull(b.updateGlobal(initialB, global("fr")));
        CaptionPreferenceStore.Snapshot next = b.updateGlobal(b.load(), global("fr"));
        assertNotNull(b.updateGlobal(next, global("zh")));
        assertNull(a.updateChannel(old, "channel-a", CaptionPreferences.Channel.defaults()));
        assertEquals("zh", a.load().preferences.global.targetLanguage);
    }

    @Test
    public void unknownVersionCorruptionAndWrongStorageTypesNeverSilentlyOverwriteOriginal() {
        SharedPreferences prefs = raw("native-profile-a");
        for (String invalid : new String[]{"not-json", "{\"version\":2}", "{\"global\":{},\"global\":{}}"}) {
            prefs.edit().putString("settings", invalid).commit();
            CaptionPreferenceStore store = store("native-profile-a");
            CaptionPreferenceStore.Snapshot value = store.load();
            assertFalse(value.valid);
            assertEquals(NativeCueComposer.Mode.BILINGUAL, value.preferences.global.mode);
            assertNull(store.updateGlobal(value, global("zh")));
            assertEquals(invalid, prefs.getString("settings", null));
        }
        prefs.edit().putInt("settings", 7).commit();
        assertFalse(store("native-profile-a").load().valid);
        assertEquals(7, prefs.getInt("settings", 0));
    }

    @Test
    public void channelCannotSmuggleGlobalOverridesOrSecretsThroughSerializedData() throws Exception {
        CaptionPreferenceStore store = store("native-profile-a");
        CaptionPreferenceStore.Snapshot value = store.updateGlobal(store.load(), global("zh"));
        store.updateChannel(value, "channel-a", CaptionPreferences.Channel.defaults());
        String valid = raw("native-profile-a").getString("settings", null);
        JSONObject altered = BoundedJson.object(valid);
        altered.getJSONObject("channels").getJSONObject("channel-a").put("target", "fr");
        raw("native-profile-a").edit().putString("settings", altered.toString()).commit();
        assertFalse(store.load().valid);
        assertEquals(altered.toString(), raw("native-profile-a").getString("settings", null));
    }

    @Test
    public void explicitBudgetsRejectWholeWriteWithoutPublishingAnAcceptedPrefix() {
        CaptionPreferenceStore tiny = new CaptionPreferenceStore(context, "native-profile-tiny", new CaptionPreferenceStore.Limits(1, 1));
        assertNull(tiny.updateGlobal(tiny.load(), global("zh")));
        assertFalse(raw("native-profile-tiny").contains("settings"));
        CaptionPreferenceStore one = new CaptionPreferenceStore(context, "native-profile-one", new CaptionPreferenceStore.Limits(1, 4096));
        CaptionPreferenceStore.Snapshot first = one.updateChannel(one.load(), "channel-a", CaptionPreferences.Channel.defaults());
        assertNotNull(first);
        assertNull(one.updateChannel(first, "channel-b", CaptionPreferences.Channel.defaults()));
        assertEquals(1, one.load().preferences.channels().size());
    }

    @Test
    public void removingOneChannelDoesNotAffectGlobalOrAnotherChannel() {
        CaptionPreferenceStore store = store("native-profile-a");
        CaptionPreferenceStore.Snapshot value = store.updateGlobal(store.load(), global("zh"));
        value = store.updateChannel(value, "channel-a", new CaptionPreferences.Channel(true, "en", CaptionSource.TextKind.UNKNOWN));
        value = store.updateChannel(value, "channel-b", new CaptionPreferences.Channel(true, "ja", CaptionSource.TextKind.UNKNOWN));
        value = store.resetChannel(value, "channel-a");
        assertFalse(value.preferences.channel("channel-a").translate);
        assertTrue(value.preferences.channel("channel-b").translate);
        assertEquals("zh", value.preferences.global.targetLanguage);
    }

    @Test
    public void lateChannelResolutionPersistsItsOwnEditsWithoutAffectingGlobalOrNextPlayback() {
        CaptionPreferenceStore store = store("native-profile-a");
        CaptionPreferenceStore.Snapshot state = store.updateGlobal(store.load(), global("zh"));
        ChannelPreferenceEditor editor = new ChannelPreferenceEditor();
        Object first = new Object();
        editor.begin(first, null, CaptionPreferences.Channel.defaults());
        editor.setTranslate(first, true);
        assertTrue(editor.effective().translate);
        assertNull(editor.pending());
        editor.bind(first, "channel-a", state.preferences.channel("channel-a"));
        ChannelPreferenceEditor.Change change = editor.pending();
        state = store.updateChannel(state, change.channelId, change.preference);
        assertNotNull(state);
        assertTrue(editor.saved(change));
        Object next = new Object();
        editor.begin(next, "channel-b", state.preferences.channel("channel-b"));
        assertFalse(editor.setSubtitle(first, "ja", CaptionSource.TextKind.UNKNOWN));
        assertTrue(store.load().preferences.channel("channel-a").translate);
        assertFalse(store.load().preferences.channel("channel-b").translate);
        assertEquals("zh", store.load().preferences.global.targetLanguage);
    }

    @Test
    public void failedCommitCannotLeakAbandonedChannelChangeIntoLaterGlobalSave() throws Exception {
        SharedPreferences backing = raw("native-profile-failing");
        boolean[] fail = {false};
        int[] failedWrites = {0};
        SharedPreferences faulting = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return invoke(method, backing, args);
                    SharedPreferences.Editor editor = backing.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                if (editMethod.getName().equals("commit") && fail[0]) {
                                    if (failedWrites[0]++ > 0) throw new IllegalStateException("Synthetic rollback storage failure");
                                    editor.commit(); // Simulates memory changed before the disk result reports failure.
                                    return false;
                                }
                                Object value = invoke(editMethod, editor, editArgs);
                                return value instanceof SharedPreferences.Editor ? editProxy : value;
                            });
                });
        CaptionPreferenceStore store = new CaptionPreferenceStore(faulting, limits);
        CaptionPreferenceStore.Snapshot old = store.updateGlobal(store.load(), global("zh"));
        fail[0] = true;
        assertNull(store.updateChannel(old, "channel-a", new CaptionPreferences.Channel(true, "en", CaptionSource.TextKind.UNKNOWN)));
        CaptionPreferenceStore.Snapshot afterFailure = store.load();
        assertFalse(afterFailure.valid);
        assertFalse(afterFailure.preferences.channel("channel-a").translate);
        assertEquals("zh", afterFailure.preferences.global.targetLanguage);
        assertNull(store.updateGlobal(afterFailure, global("fr")));
        CaptionPreferenceStore another = new CaptionPreferenceStore(faulting, limits);
        assertFalse(another.load().valid);
        assertFalse(another.load().preferences.channel("channel-a").translate);
        assertNull(store.recoverFailedWrite());
        fail[0] = false;
        CaptionPreferenceStore.Snapshot recovered = store.recoverFailedWrite();
        assertNotNull(recovered);
        assertTrue(recovered.valid);
        assertFalse(recovered.preferences.channel("channel-a").translate);
        assertTrue(another.load().valid);
        CaptionPreferenceStore.Snapshot updated = store.updateGlobal(recovered, global("fr"));
        assertNotNull(updated);
        assertEquals("fr", updated.preferences.global.targetLanguage);
        assertFalse(updated.preferences.channel("channel-a").translate);
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
    }

    private CaptionPreferenceStore store(String scope) { return new CaptionPreferenceStore(context, scope, limits); }
    private SharedPreferences raw(String scope) { return context.getSharedPreferences(CaptionPreferenceStore.storageName(scope), Context.MODE_PRIVATE); }
    private static CaptionPreferences.Global global(String target) {
        return new CaptionPreferences.Global(target, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST, "connection-main");
    }
}
