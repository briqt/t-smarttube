package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;

/** Pending channel writes while metadata arrives, not a third user preference layer. No I/O or requests. */
public final class ChannelPreferenceEditor {
    public static final class Change {
        public final String channelId;
        public final CaptionPreferences.Channel preference;
        private final ChannelPreferenceEditor mOwner;
        private final Object mSource;
        private final long mRevision;
        private Change(ChannelPreferenceEditor owner, Object source, long revision,
                       String channelId, CaptionPreferences.Channel preference) {
            mOwner = owner;
            mSource = source;
            mRevision = revision;
            this.channelId = channelId;
            this.preference = preference;
        }
    }

    private Object mSource;
    private String mChannelId;
    private CaptionPreferences.Channel mWorking = CaptionPreferences.Channel.defaults();
    private boolean mTranslateChanged;
    private boolean mSubtitleChanged;
    private long mRevision;

    /** Token follows native video/account lifetime, not a Raw sample, seek position, or persistent video ID. */
    public synchronized void begin(Object source, String channelId, CaptionPreferences.Channel stored) {
        if (source == null || channelId != null && !CaptionPreferences.identifier(channelId) || stored == null) {
            throw CaptionPreferences.invalid();
        }
        mSource = source;
        mChannelId = channelId;
        mWorking = channelId == null ? CaptionPreferences.Channel.defaults() : stored;
        mTranslateChanged = false;
        mSubtitleChanged = false;
        mRevision++;
    }

    /** Only fields the user changed before channel resolution override fresh channel data. */
    public synchronized boolean bind(Object source, String channelId, CaptionPreferences.Channel stored) {
        if (source == null || source != mSource) return false;
        if (!CaptionPreferences.identifier(channelId) || stored == null) throw CaptionPreferences.invalid();
        if (mChannelId != null && !mChannelId.equals(channelId)) return false;
        mWorking = new CaptionPreferences.Channel(mTranslateChanged ? mWorking.translate : stored.translate,
                mSubtitleChanged ? mWorking.subtitleLanguage : stored.subtitleLanguage,
                mSubtitleChanged ? mWorking.subtitleKind : stored.subtitleKind);
        mChannelId = channelId;
        mRevision++;
        return true;
    }

    public synchronized boolean setTranslate(Object source, boolean enabled) {
        if (source == null || source != mSource) return false;
        mWorking = new CaptionPreferences.Channel(enabled, mWorking.subtitleLanguage, mWorking.subtitleKind);
        mTranslateChanged = true;
        mRevision++;
        return true;
    }

    public synchronized boolean setSubtitle(Object source, String language, CaptionSource.TextKind kind) {
        if (source == null || source != mSource) return false;
        mWorking = new CaptionPreferences.Channel(mWorking.translate, language, kind);
        mSubtitleChanged = true;
        mRevision++;
        return true;
    }

    /** Edits can take effect immediately even if the channel ID is not yet available. */
    public synchronized CaptionPreferences.Channel effective() {
        return mWorking;
    }

    public synchronized Change pending() {
        return mSource != null && mChannelId != null && (mTranslateChanged || mSubtitleChanged) ?
                new Change(this, mSource, mRevision, mChannelId, mWorking) : null;
    }

    public synchronized boolean isCurrent(Change change) {
        return change != null && change.mOwner == this && change.mSource == mSource && change.mRevision == mRevision &&
                change.channelId.equals(mChannelId) && (mTranslateChanged || mSubtitleChanged);
    }

    /** A failed or obsolete write must not clear newer edits. Call only after successful persistence. */
    public synchronized boolean saved(Change change) {
        if (!isCurrent(change)) return false;
        mTranslateChanged = false;
        mSubtitleChanged = false;
        return true;
    }

    public synchronized void clear() {
        mSource = null;
        mChannelId = null;
        mWorking = CaptionPreferences.Channel.defaults();
        mTranslateChanged = false;
        mSubtitleChanged = false;
        mRevision++;
    }
}
