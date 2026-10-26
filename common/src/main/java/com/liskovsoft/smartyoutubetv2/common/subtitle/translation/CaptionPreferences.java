package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Two user-setting layers. Native font/background/placement remain owned by the original player. */
public final class CaptionPreferences {
    public static final class Global {
        public final String targetLanguage;
        public final NativeCueComposer.Mode mode;
        public final NativeCueComposer.Order order;
        /** Reference to globally managed connection/request options, never an endpoint, Key or channel override. */
        public final String connectionId;

        public Global(String targetLanguage, NativeCueComposer.Mode mode, NativeCueComposer.Order order, String connectionId) {
            if (targetLanguage != null && !TranslationBatch.language(targetLanguage) || mode == null || order == null ||
                    connectionId != null && !identifier(connectionId)) throw invalid();
            this.targetLanguage = targetLanguage;
            this.mode = mode;
            this.order = order;
            this.connectionId = connectionId;
        }

        public boolean hasSameRequestSettings(Global other) {
            return other != null && equal(targetLanguage, other.targetLanguage) && equal(connectionId, other.connectionId);
        }

        public boolean hasSamePresentation(Global other) {
            return other != null && mode == other.mode && order == other.order;
        }

        public static Global defaults() {
            // No target language/connection is guessed and no paid request is enabled by a display default.
            return new Global(null, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST, null);
        }
    }

    public static final class Channel {
        public final boolean translate;
        public final String subtitleLanguage;
        /** A preference hint only; missing evidence or a missing preferred track must not disable translation. */
        public final CaptionSource.TextKind subtitleKind;

        public Channel(boolean translate, String subtitleLanguage, CaptionSource.TextKind subtitleKind) {
            if (subtitleKind == null || subtitleLanguage != null && !TranslationBatch.language(subtitleLanguage) ||
                    subtitleLanguage == null && subtitleKind != CaptionSource.TextKind.UNKNOWN) throw invalid();
            this.translate = translate;
            this.subtitleLanguage = subtitleLanguage;
            this.subtitleKind = subtitleKind;
        }

        public static Channel defaults() {
            return new Channel(false, null, CaptionSource.TextKind.UNKNOWN);
        }
    }

    public final Global global;
    private final Map<String, Channel> mChannels;

    public CaptionPreferences(Global global, Map<String, Channel> channels) {
        if (global == null || channels == null || channels.size() > 8192) throw invalid();
        Map<String, Channel> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            if (!identifier(entry.getKey()) || entry.getValue() == null) throw invalid();
            copy.put(entry.getKey(), entry.getValue());
        }
        this.global = global;
        mChannels = Collections.unmodifiableMap(copy);
    }

    public Channel channel(String channelId) {
        Channel channel = mChannels.get(channelId);
        return channel == null ? Channel.defaults() : channel;
    }

    public Map<String, Channel> channels() {
        return mChannels;
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    static boolean identifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid caption preferences");
    }
}
