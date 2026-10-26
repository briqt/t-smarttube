package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChannelPreferenceEditor;
import java.util.List;

/** Native user intent survives late metadata; automatic restoration never becomes another user edit. */
public final class CaptionSelectionMemory {
    public interface Sources { CaptionSource source(FormatItem format); }
    private final ChannelPreferenceEditor mEditor = new ChannelPreferenceEditor();
    private Object mSource;
    private String mChannel;
    private FormatItem mManual;

    public void begin(Object source, String channel, CaptionPreferences preferences) {
        mSource = source;
        mChannel = channel;
        mManual = null;
        mEditor.begin(source, channel, channel == null ? CaptionPreferences.Channel.defaults() : preferences.channel(channel));
    }
    public boolean bind(Object source, String channel, CaptionPreferences preferences) {
        if (source != mSource || channel == null) return false;
        boolean bound = mEditor.bind(source, channel, preferences.channel(channel));
        if (bound) mChannel = channel;
        return bound;
    }
    public boolean translate(Object source, boolean enabled) { return mEditor.setTranslate(source, enabled); }
    public boolean selected(Object source, FormatItem format, CaptionSource descriptor) {
        if (source == null || source != mSource || format == null) return false;
        mManual = format;
        String language = format.isDefault() ? null : language(descriptor);
        CaptionSource.TextKind kind = language == null || descriptor == null ? CaptionSource.TextKind.UNKNOWN : descriptor.getTextKind();
        return mEditor.setSubtitle(source, language, kind);
    }
    public FormatItem restore(FormatItem nativeFallback, List<FormatItem> formats, Sources sources) {
        if (formats == null) return nativeFallback;
        if (mManual != null) {
            if (mManual.isDefault()) return FormatItem.SUBTITLE_NONE;
            for (FormatItem format : formats) if (mManual.equals(format)) return format;
            return nativeFallback;
        }
        CaptionPreferences.Channel wanted = mEditor.effective();
        if (wanted.subtitleLanguage == null) return nativeFallback;
        FormatItem fallback = null;
        for (FormatItem format : formats) {
            if (format == null || format.isDefault()) continue;
            CaptionSource source = sources.source(format);
            if (!wanted.subtitleLanguage.equalsIgnoreCase(language(source))) continue;
            if (format.equals(nativeFallback)) return format;
            if (wanted.subtitleKind == CaptionSource.TextKind.UNKNOWN || source.getTextKind() == wanted.subtitleKind) return format;
            if (fallback == null) fallback = format;
        }
        return fallback == null ? nativeFallback : fallback;
    }
    public String channel() { return mChannel; }
    public CaptionPreferences.Channel effective() { return mEditor.effective(); }
    public ChannelPreferenceEditor.Change pending() { return mEditor.pending(); }
    public boolean saved(ChannelPreferenceEditor.Change change) { return mEditor.saved(change); }
    public void clear() { mSource = null; mChannel = null; mManual = null; mEditor.clear(); }

    private static String language(CaptionSource source) {
        if (source == null || !source.getProblems().isEmpty()) return null;
        String value = source.getTranslationLanguage() != null ? source.getTranslationLanguage() :
                source.getCatalogLanguage() != null ? source.getCatalogLanguage() : source.getSourceLanguage();
        try { new CaptionPreferences.Channel(false, value, source.getTextKind()); return value; }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
