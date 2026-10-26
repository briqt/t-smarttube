package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import com.google.android.exoplayer2.Format;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChannelPreferenceEditor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionSelectionMemoryTest {
    private final FormatItem english = format("en", "English");
    private final FormatItem french = format("fr", "French");
    private final CaptionSource en = source("en"), fr = source("fr");
    private final CaptionSelectionMemory memory = new CaptionSelectionMemory();

    @Test public void manualSelectionBeforeMetadataIsNotOverwrittenAndOnlyChangedFieldsAreMerged() {
        Object video = new Object();
        memory.begin(video, null, defaults());
        memory.selected(video, english, en);
        assertSame(english, memory.restore(FormatItem.SUBTITLE_NONE, Arrays.asList(english, french), this::source));
        assertTrue(memory.bind(video, "channel-a", preferences("fr", true)));
        assertTrue(memory.effective().translate);
        assertEquals("en", memory.effective().subtitleLanguage);
        assertSame(english, memory.restore(french, Arrays.asList(english, french), this::source));
        ChannelPreferenceEditor.Change change = memory.pending();
        assertEquals("channel-a", change.channelId);
        assertTrue(memory.saved(change));
        assertNull(memory.pending());
    }
    @Test public void explicitNoneSurvivesLateMetadataButDoesNotChangeAiPreference() {
        Object video = new Object();
        memory.begin(video, null, defaults());
        memory.translate(video, true);
        memory.selected(video, FormatItem.SUBTITLE_NONE, null);
        memory.bind(video, "channel-a", preferences("fr", false));
        assertTrue(memory.effective().translate);
        assertNull(memory.effective().subtitleLanguage);
        assertSame(FormatItem.SUBTITLE_NONE, memory.restore(french, Arrays.asList(english, french), this::source));
    }
    @Test public void storedLanguageRestoresWithoutBecomingAnotherUserWriteAndMissingTrackUsesNativeFallback() {
        memory.begin(new Object(), "channel-a", preferences("fr", true));
        assertSame(french, memory.restore(english, Arrays.asList(english, french), this::source));
        assertNull(memory.pending());
        assertSame(english, memory.restore(english, Collections.singletonList(english), this::source));
        assertSame(FormatItem.SUBTITLE_NONE, memory.restore(FormatItem.SUBTITLE_NONE, Collections.singletonList(english), this::source));
        assertTrue(memory.effective().translate); // Missing preferred track is never an AI disable condition.
    }
    @Test public void unknownDescriptorNeverStoresDisplayNameOrPreventsCurrentNativeSelection() {
        Object video = new Object();
        memory.begin(video, "channel-a", preferences("fr", true));
        memory.selected(video, english, null);
        assertNull(memory.effective().subtitleLanguage);
        assertEquals(CaptionSource.TextKind.UNKNOWN, memory.effective().subtitleKind);
        assertSame(english, memory.restore(french, Arrays.asList(english, french), item -> null));
        assertTrue(memory.effective().translate);
    }
    @Test public void sourceAbaAndForeignMetadataOrAcknowledgementsCannotChangeNewPlayback() {
        Object a = new Object(), b = new Object(), aAgain = new Object();
        memory.begin(a, "channel-a", defaults());
        memory.translate(a, true);
        ChannelPreferenceEditor.Change old = memory.pending();
        memory.begin(b, "channel-b", defaults());
        assertFalse(memory.bind(a, "channel-a", preferences("fr", true)));
        assertFalse(memory.selected(a, french, fr));
        assertFalse(memory.saved(old));
        memory.begin(aAgain, "channel-a", defaults());
        assertFalse(memory.bind(a, "channel-a", preferences("fr", true)));
        assertFalse(memory.translate(a, true));
        assertFalse(memory.saved(old));
        assertFalse(memory.effective().translate);
        assertSame(english, memory.restore(english, Arrays.asList(english, french), this::source));
    }
    @Test public void lateStoredDataKeepsPendingTranslateAndKnownChannelCannotBeReassigned() {
        Object video = new Object();
        memory.begin(video, "channel-a", defaults());
        memory.translate(video, true);
        memory.bind(video, "channel-a", preferences("fr", false));
        assertTrue(memory.effective().translate);
        assertEquals("fr", memory.effective().subtitleLanguage);
        assertFalse(memory.bind(video, "channel-b", defaults()));
        assertEquals("channel-a", memory.channel());
        memory.clear();
        assertNull(memory.channel());
        assertNull(memory.pending());
        assertFalse(memory.selected(video, french, fr));
    }
    @Test public void noFormatsAndUnavailableManualTrackUseNativeFallbackWithoutInventingATrack() {
        Object video = new Object();
        memory.begin(video, null, defaults());
        memory.selected(video, french, fr);
        assertSame(english, memory.restore(english, null, this::source));
        assertSame(english, memory.restore(english, Collections.singletonList(english), this::source));
    }

    private CaptionSource source(FormatItem item) { return item == french ? fr : item == english ? en : null; }
    private static FormatItem format(String id, String displayName) {
        return ExoFormatItem.from(Format.createTextSampleFormat(id, "text/vtt", 0, displayName));
    }
    private static CaptionPreferences defaults() { return new CaptionPreferences(CaptionPreferences.Global.defaults(), Collections.emptyMap()); }
    private static CaptionPreferences preferences(String language, boolean translate) {
        return new CaptionPreferences(CaptionPreferences.Global.defaults(), Collections.singletonMap("channel-a",
                new CaptionPreferences.Channel(translate, language, CaptionSource.TextKind.UNKNOWN)));
    }
    private static CaptionSource source(String language) {
        return CaptionSourceClassifier.classify(new MediaSubtitle() {
            public String getBaseUrl() { return "https://www.youtube.com/api/timedtext?v=synthetic&lang=" + language; }
            public boolean isTranslatable() { return true; }
            public String getLanguageCode() { return language; }
            public String getVssId() { return "." + language; }
            public String getName() { return "Synthetic"; }
            public String getMimeType() { return "text/vtt"; }
            public String getCodecs() { return null; }
            public String getType() { return null; }
        });
    }
}
