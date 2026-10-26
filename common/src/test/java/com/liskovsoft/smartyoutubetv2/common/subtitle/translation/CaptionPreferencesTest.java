package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CaptionPreferencesTest {
    @Test
    public void displayDefaultsAreExplicitWithoutGuessingTargetConnectionOrEnablingTranslation() {
        CaptionPreferences.Global global = CaptionPreferences.Global.defaults();
        assertEquals(NativeCueComposer.Mode.BILINGUAL, global.mode);
        assertEquals(NativeCueComposer.Order.ORIGINAL_FIRST, global.order);
        assertNull(global.targetLanguage);
        assertNull(global.connectionId);
        assertFalse(CaptionPreferences.Channel.defaults().translate);
    }

    @Test
    public void channelValuesCannotOverrideGlobalFieldsAndMapsAreFrozen() {
        CaptionPreferences.Global global = global("zh", NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST);
        Map<String, CaptionPreferences.Channel> channels = new LinkedHashMap<>();
        channels.put("channel-a", new CaptionPreferences.Channel(true, "en", CaptionSource.TextKind.NON_ASR_CANDIDATE));
        CaptionPreferences preferences = new CaptionPreferences(global, channels);
        channels.clear();
        assertSame(global, preferences.global);
        assertTrue(preferences.channel("channel-a").translate);
        assertFalse(preferences.channel("channel-b").translate);
        assertNull(preferences.channel(null).subtitleLanguage);
        try { preferences.channels().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        for (java.lang.reflect.Field field : CaptionPreferences.Channel.class.getFields()) {
            assertFalse(field.getName().contains("target"));
            assertFalse(field.getName().contains("connection"));
            assertFalse(field.getName().contains("key"));
            assertFalse(field.getName().contains("mode"));
        }
    }

    @Test
    public void presentationChangesDoNotChangeRequestSettingsButTargetChangesDo() {
        CaptionPreferences.Global first = global("zh", NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST);
        CaptionPreferences.Global reversed = global("zh", NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.TRANSLATION_FIRST);
        CaptionPreferences.Global translated = global("zh", NativeCueComposer.Mode.TRANSLATION, NativeCueComposer.Order.ORIGINAL_FIRST);
        assertTrue(first.hasSameRequestSettings(reversed));
        assertTrue(first.hasSameRequestSettings(translated));
        assertFalse(first.hasSamePresentation(reversed));
        assertFalse(first.hasSameRequestSettings(global("fr", first.mode, first.order)));
    }

    @Test
    public void invalidLanguageIdentifierOrKindDoesNotBecomeAStoredPreference() {
        invalid(() -> new CaptionPreferences.Channel(true, "English name", CaptionSource.TextKind.UNKNOWN));
        invalid(() -> new CaptionPreferences.Channel(true, null, CaptionSource.TextKind.ASR_CANDIDATE));
        invalid(() -> new CaptionPreferences.Global("zh", NativeCueComposer.Mode.BILINGUAL,
                NativeCueComposer.Order.ORIGINAL_FIRST, "https://example.invalid/key"));
    }

    static CaptionPreferences.Global global(String target, NativeCueComposer.Mode mode, NativeCueComposer.Order order) {
        return new CaptionPreferences.Global(target, mode, order, "connection-main");
    }
    private static void invalid(Runnable task) {
        try { task.run(); fail(); } catch (IllegalArgumentException expected) { assertEquals("Invalid caption preferences", expected.getMessage()); }
    }
}
