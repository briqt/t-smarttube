package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelPreferenceEditorTest {
    @Test
    public void unknownChannelEditTakesEffectImmediatelyAndOnlyChangedFieldsMergeWhenResolved() {
        ChannelPreferenceEditor editor = new ChannelPreferenceEditor();
        Object source = new Object();
        editor.begin(source, null, new CaptionPreferences.Channel(false, "ja", CaptionSource.TextKind.UNKNOWN));
        assertNull(editor.effective().subtitleLanguage); // Never inherit another/unknown channel's preference.
        assertTrue(editor.setTranslate(source, true));
        assertTrue(editor.effective().translate);
        assertNull(editor.pending());
        assertTrue(editor.bind(source, "channel-a", new CaptionPreferences.Channel(false, "en", CaptionSource.TextKind.ASR_CANDIDATE)));
        ChannelPreferenceEditor.Change change = editor.pending();
        assertEquals("channel-a", change.channelId);
        assertTrue(change.preference.translate);
        assertEquals("en", change.preference.subtitleLanguage);
        assertTrue(editor.saved(change));
        assertNull(editor.pending());
    }

    @Test
    public void pendingSubtitleEditDoesNotOverwriteStoredTranslationSetting() {
        ChannelPreferenceEditor editor = new ChannelPreferenceEditor();
        Object source = new Object();
        editor.begin(source, null, CaptionPreferences.Channel.defaults());
        assertTrue(editor.setSubtitle(source, "fr", CaptionSource.TextKind.NON_ASR_CANDIDATE));
        editor.bind(source, "channel-a", new CaptionPreferences.Channel(true, "en", CaptionSource.TextKind.UNKNOWN));
        assertTrue(editor.effective().translate);
        assertEquals("fr", editor.effective().subtitleLanguage);
    }

    @Test
    public void lateMetadataFromPreviousPlaybackCannotSaveToTheNewChannel() {
        ChannelPreferenceEditor editor = new ChannelPreferenceEditor();
        Object old = new Object(), current = new Object();
        editor.begin(old, null, CaptionPreferences.Channel.defaults());
        editor.setTranslate(old, true);
        editor.begin(current, "channel-b", CaptionPreferences.Channel.defaults());
        assertFalse(editor.bind(old, "channel-a", CaptionPreferences.Channel.defaults()));
        assertFalse(editor.setTranslate(old, true));
        assertFalse(editor.effective().translate);
        assertNull(editor.pending());
        assertFalse(editor.bind(current, "different-channel", CaptionPreferences.Channel.defaults()));
    }

    @Test
    public void delayedWriteAcknowledgementCannotDropANewerUserEditOrAcceptForeignEditorChange() {
        ChannelPreferenceEditor editor = new ChannelPreferenceEditor();
        Object source = new Object();
        editor.begin(source, "channel-a", CaptionPreferences.Channel.defaults());
        editor.setTranslate(source, true);
        ChannelPreferenceEditor.Change first = editor.pending();
        editor.setSubtitle(source, "en", CaptionSource.TextKind.UNKNOWN);
        assertFalse(editor.saved(first));
        ChannelPreferenceEditor.Change next = editor.pending();
        assertEquals("en", next.preference.subtitleLanguage);
        ChannelPreferenceEditor other = new ChannelPreferenceEditor();
        other.begin(source, "channel-a", CaptionPreferences.Channel.defaults());
        other.setTranslate(source, true);
        assertFalse(other.saved(next));
        assertTrue(editor.saved(next));
    }

    @Test
    public void clearRejectsAllOldChangesWithoutCreatingAUserVisibleThirdLayer() {
        ChannelPreferenceEditor editor = new ChannelPreferenceEditor();
        Object source = new Object();
        editor.begin(source, "channel-a", CaptionPreferences.Channel.defaults());
        editor.setTranslate(source, true);
        ChannelPreferenceEditor.Change old = editor.pending();
        editor.clear();
        assertFalse(editor.isCurrent(old));
        assertFalse(editor.setTranslate(source, true));
        assertFalse(editor.effective().translate);
        assertNull(editor.pending());
    }
}
