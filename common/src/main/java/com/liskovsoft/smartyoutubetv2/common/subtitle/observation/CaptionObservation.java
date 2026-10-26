package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import android.os.SystemClock;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionLoader;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionRequest;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.NativeSubtitleProjection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.AsrTranscriptPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-engine caption input bridge with optional diagnostics. Document ownership stays in CaptionSession. */
public final class CaptionObservation implements SubtitleDecoderFactory {
    public enum Route { DASH_CATALOG, SABR_CATALOG }
    enum Capture { CAPTURED, UNSUPPORTED_BUFFER, TOO_LARGE, ANALYSIS_FAILED }
    interface Sink { void accept(Report report); }
    private static final int MAX_CATALOG = 512;
    private static final int MAX_DIAGNOSTIC_SAMPLES_PER_DECODER = 32;
    // Bounded business document profile, not a sample-count admission or network-request policy.
    private static final RawTimeline.Limits TIMELINE_LIMITS =
            new RawTimeline.Limits(CaptionSampleMetrics.MAX_BYTES, 4096, 32768);
    private static final SemanticGroupPlanner.Limits GROUP_LIMITS =
            new SemanticGroupPlanner.Limits(4096, 32768, 32768);

    enum TimelineState { SKIPPED, ACCEPTED, REJECTED }

    /** Diagnostic reports contain only safe scalars; text and bytes belong to the session. */
    static final class TimelineSummary {
        final TimelineState state;
        final RawTimeline.Origin origin;
        final int cues;
        final int runs;
        final int metadata;
        final int normalization;
        final int semanticAtoms;
        final int semanticGroups;
        final int semanticReferences;
        final int semanticEchoes;
        final int sourceCodePoints;
        final int semanticCodePoints;
        final Long minUs;
        final Long maxUs;

        TimelineSummary(TimelineState state, RawTimeline timeline, MappedTranscript transcript, SemanticGroupPlanner.Plan groups) {
            this.state = state;
            origin = timeline == null ? null : timeline.getOrigin();
            int count = 0, sourcePoints = 0;
            Long min = null;
            Long max = null;
            if (timeline != null) {
                for (RawTimeline.Cue cue : timeline.getCues()) {
                    count += cue.runs.size();
                    sourcePoints += cue.mappingText.codePointCount(0, cue.mappingText.length());
                    min = min == null ? cue.startUs : Math.min(min, cue.startUs);
                    max = max == null ? cue.endUs : Math.max(max, cue.endUs);
                }
            }
            cues = timeline == null ? 0 : timeline.getCues().size();
            runs = count;
            metadata = timeline == null ? 0 : timeline.getMetadata().size();
            sourceCodePoints = sourcePoints;
            normalization = transcript == null ? 0 : transcript.normalizationVersion();
            semanticAtoms = transcript == null ? 0 : transcript.getAtoms().size();
            semanticGroups = groups == null ? 0 : groups.getGroups().size();
            int references = 0, echoes = 0, points = 0;
            if (transcript != null) for (MappedTranscript.Atom atom : transcript.getAtoms()) {
                references += atom.origins.size();
                points += atom.text.codePointCount(0, atom.text.length());
                for (MappedTranscript.Origin origin : atom.origins) if (origin.echo) echoes++;
            }
            semanticReferences = references;
            semanticEchoes = echoes;
            semanticCodePoints = points;
            minUs = min;
            maxUs = max;
        }
    }

    static final class Binding {
        // These survive decoder/NONE changes only inside this actual source + Format binding.
        final CaptionSession.CacheScope currentScope = new CaptionSession.CacheScope();
        final CaptionSession.CacheScope originalScope;
        final int candidates;
        final CaptionSource source;
        Binding(int candidates, CaptionSource source, boolean provenParentClock) {
            this.candidates = candidates;
            this.source = source;
            // Only find()'s unique same-video/vss/lang/MIME parent and standalone clock permit this alias.
            // Exact text, input clock, mapping, language and wire semantics still have to match the cache key.
            originalScope = provenParentClock ? currentScope : new CaptionSession.CacheScope();
        }
    }

    static final class Lease {
        final long sourceGeneration;
        final long decoderId;
        final Binding binding;
        final String mime;
        final Route route;
        final long durationMs;
        final CaptionSession.Track track;
        long revision;
        int diagnosticSamples;
        int diagnosticReports;
        boolean released;
        Lease(long sourceGeneration, long decoderId, Binding binding, String mime, Route route, long durationMs,
              CaptionSession.Track track) {
            this.sourceGeneration = sourceGeneration;
            this.decoderId = decoderId;
            this.binding = binding;
            this.mime = mime;
            this.route = route;
            this.durationMs = durationMs;
            this.track = track;
        }
    }

    /** Captured on the renderer thread before its UI message is posted; never a stable track ID. */
    public static final class OutputStamp {
        private final CaptionObservation mOwner;
        private final Lease mLease;
        private final long mRevision;
        private final long mOutputGeneration;
        private final boolean mOutputReady;
        private OutputStamp(CaptionObservation owner, Lease lease, long revision, long outputGeneration, boolean outputReady) {
            mOwner = owner;
            mLease = lease;
            mRevision = revision;
            mOutputGeneration = outputGeneration;
            mOutputReady = outputReady;
        }
    }

    private static final class CapturedInput {
        final Lease lease;
        final long revision;
        final byte[] bytes;
        final Capture capture;
        final long timeUs;
        final long offsetUs;
        final boolean decodeOnly;
        final boolean keyFrame;
        final int sample;
        boolean projectionTried;
        boolean analyzed;
        CapturedInput(Lease lease, long revision, byte[] bytes, Capture capture, long timeUs, long offsetUs,
                      boolean decodeOnly, boolean keyFrame, int sample) {
            this.lease = lease;
            this.revision = revision;
            this.bytes = bytes;
            this.capture = capture;
            this.timeUs = timeUs;
            this.offsetUs = offsetUs;
            this.decodeOnly = decodeOnly;
            this.keyFrame = keyFrame;
            this.sample = sample;
        }
    }

    static final class Report {
        final Lease lease;
        final long revision;
        final int sample;
        final long timeUs;
        final long offsetUs;
        final boolean decodeOnly;
        final boolean keyFrame;
        final Capture capture;
        final CaptionSampleMetrics.Result metrics;
        final TimelineSummary timeline;
        final boolean sessionAccepted;
        final boolean replayed;

        Report(Lease lease, long revision, int sample, long timeUs, long offsetUs, boolean decodeOnly,
               boolean keyFrame, Capture capture, CaptionSampleMetrics.Result metrics, TimelineSummary timeline,
               boolean sessionAccepted, boolean replayed) {
            this.lease = lease;
            this.revision = revision;
            this.sample = sample;
            this.timeUs = timeUs;
            this.offsetUs = offsetUs;
            this.decodeOnly = decodeOnly;
            this.keyFrame = keyFrame;
            this.capture = capture;
            this.metrics = metrics;
            this.timeline = timeline;
            this.sessionAccepted = sessionAccepted;
            this.replayed = replayed;
        }

        String safeLine() {
            CaptionSource source = lease.binding.source;
            String line = "source=" + lease.sourceGeneration + " decoder=" + lease.decoderId +
                    " revision=" + revision + " sample=" + sample + " route=" + lease.route +
                    " candidates=" + lease.binding.candidates + " textKind=" +
                    (source == null ? CaptionSource.TextKind.UNKNOWN : source.getTextKind()) + " translation=" +
                    (source == null ? CaptionSource.TranslationKind.UNKNOWN : source.getTranslationKind()) +
                    " sourceProblems=" + (source == null ? -1 : source.getProblems().size()) +
                    " format=" + ("text/vtt".equals(lease.mime) ? "VTT" : "TTML") +
                    " durationMs=" + lease.durationMs + " timeUs=" + timeUs + " offsetUs=" + offsetUs +
                    " decodeOnly=" + decodeOnly + " keyFrame=" + keyFrame + " capture=" + capture;
            if (metrics != null) {
                line += " analysis=" + metrics.status + " bytes=" + metrics.bytes + " cues=" + metrics.cueCount +
                        " settings=" + metrics.cuesWithSettings + " timestamps=" + metrics.inlineTimestamps +
                        " timestampMap=" + metrics.hasTimestampMap + " style=" + metrics.hasStyleBlock +
                        " region=" + metrics.hasRegionBlock + " documentMinUs=" + metrics.minStartUs +
                        " documentMaxUs=" + metrics.maxEndUs;
            }
            return line + " rawState=" + timeline.state + " rawCues=" + timeline.cues +
                    " rawRuns=" + timeline.runs + " rawMetadata=" + timeline.metadata + " rawMinUs=" + timeline.minUs +
                    " rawMaxUs=" + timeline.maxUs + " normalization=" + timeline.normalization + " semanticAtoms=" + timeline.semanticAtoms +
                    " semanticGroups=" + timeline.semanticGroups + " semanticReferences=" + timeline.semanticReferences +
                    " semanticEchoes=" + timeline.semanticEchoes + " sourceCodePoints=" + timeline.sourceCodePoints +
                    " semanticCodePoints=" + timeline.semanticCodePoints +
                    " inputOrigin=" + timeline.origin + " sessionAccepted=" + sessionAccepted + " replayed=" + replayed;
        }
    }

    private static final class Entry {
        final String id;
        final String language;
        final String mime;
        final CaptionSource source;
        Entry(MediaSubtitle subtitle) {
            id = subtitle.getVssId();
            String name = subtitle.getName();
            language = Util.normalizeLanguageCode(name == null ? subtitle.getLanguageCode() : name);
            mime = subtitle.getMimeType();
            source = CaptionSourceClassifier.classify(subtitle);
        }
        boolean matches(Format format) {
            return Util.areEqual(id, format.id) && Util.areEqual(language, format.language) &&
                    Util.areEqual(mime, format.sampleMimeType);
        }
    }

    private final SubtitleDecoderFactory mDelegate;
    private final Executor mExecutor;
    private final Sink mSink;
    private final ThreadPoolExecutor mOwnedExecutor;
    private final Map<Format, Binding> mFormats = new IdentityHashMap<>();
    private final CaptionSession mSession = new CaptionSession();
    private final Map<Format, NativeCaptionRequest> mOriginalRequests = new IdentityHashMap<>();
    private final CaptionInputSession mInputs;
    private boolean mEnhancementEnabled;
    private CapturedInput mLastInput;
    private long mSourceGeneration;
    private long mDecoderId;
    private long mOutputGeneration;
    private boolean mAwaitingOutputReset;
    private Route mRoute;
    private long mDurationMs;
    private Lease mCurrent;
    private boolean mClosed;

    public static CaptionObservation create(Context context) {
        boolean diagnostics = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), runnable -> new Thread(runnable, "caption-observation"),
                new ThreadPoolExecutor.AbortPolicy());
        Sink sink = diagnostics ? report -> Log.d("CaptionObservation", report.safeLine()) : null;
        return new CaptionObservation(SubtitleDecoderFactory.DEFAULT, worker, sink, worker);
    }

    CaptionObservation(SubtitleDecoderFactory delegate, Executor executor, Sink sink) {
        this(delegate, executor, sink, null);
    }

    private CaptionObservation(SubtitleDecoderFactory delegate, Executor executor, Sink sink,
                               ThreadPoolExecutor ownedExecutor) {
        mDelegate = delegate;
        mExecutor = executor;
        mSink = sink;
        mOwnedExecutor = ownedExecutor;
        // Development admission limits, not persisted product defaults or a final waiting-time choice.
        mInputs = new CaptionInputSession(mSession, SystemClock::elapsedRealtime, new NativeCaptionLoader(),
                new CaptionInputSession.Limits(1000, 1000, 2, TIMELINE_LIMITS));
    }

    public void bindSource(MediaItemFormatInfo info, com.google.android.exoplayer2.source.dash.manifest.DashManifest manifest) {
        bindSource(info, manifest, null);
    }

    public void bindSource(MediaItemFormatInfo info, com.google.android.exoplayer2.source.dash.manifest.DashManifest manifest,
                           DataSource.Factory factory) {
        boolean clock = !manifest.dynamic && manifest.getPeriodCount() == 1 && manifest.getPeriod(0).startMs == 0 && manifest.durationMs > 0;
        List<Format> formats = new ArrayList<>();
        for (int i = 0; i < manifest.getPeriodCount(); i++) {
            for (com.google.android.exoplayer2.source.dash.manifest.AdaptationSet set : manifest.getPeriod(i).adaptationSets) {
                if (set.type == com.google.android.exoplayer2.C.TRACK_TYPE_TEXT) {
                    for (com.google.android.exoplayer2.source.dash.manifest.Representation representation : set.representations) {
                        clock &= representation.presentationTimeOffsetUs == 0;
                        formats.add(representation.format);
                    }
                }
            }
        }
        bindSource(info, Route.DASH_CATALOG, formats, manifest.durationMs, factory, manifest.visitorCookie, clock);
    }

    public void bindSource(MediaItemFormatInfo info, com.google.android.exoplayer2.source.sabr.manifest.SabrManifest manifest) {
        bindSource(info, manifest, null);
    }

    public void bindSource(MediaItemFormatInfo info, com.google.android.exoplayer2.source.sabr.manifest.SabrManifest manifest,
                           DataSource.Factory factory) {
        boolean clock = !manifest.dynamic && manifest.periods.size() == 1 && manifest.periods.get(0).startMs == 0 && manifest.durationMs > 0;
        List<Format> formats = new ArrayList<>();
        for (com.google.android.exoplayer2.source.sabr.manifest.Period period : manifest.periods) {
            for (com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet set : period.adaptationSets) {
                if (set.type == com.google.android.exoplayer2.C.TRACK_TYPE_TEXT) {
                    for (com.google.android.exoplayer2.source.sabr.manifest.Representation representation : set.representations) {
                        clock &= representation.presentationTimeOffsetUs == 0;
                        formats.add(representation.format);
                    }
                }
            }
        }
        bindSource(info, Route.SABR_CATALOG, formats, manifest.durationMs, factory, manifest.visitorCookie, clock);
    }

    /** Bind actual manifest identities even when provenance metadata is unavailable. */
    public void bindSource(MediaItemFormatInfo info, Route route, List<Format> formats, long durationMs) {
        bindSource(info, route, formats, durationMs, null, null, false);
    }

    private synchronized void bindSource(MediaItemFormatInfo info, Route route, List<Format> formats, long durationMs,
                                         DataSource.Factory factory, String visitorCookie, boolean clock) {
        clearSource();
        if (mClosed || formats == null || formats.size() > MAX_CATALOG) return;
        List<MediaSubtitle> subtitles = null;
        List<Entry> entries = new ArrayList<>();
        try {
            List<MediaSubtitle> supplied = info == null ? null : info.getSubtitles();
            if (supplied != null && supplied.size() <= MAX_CATALOG) {
                subtitles = new ArrayList<>(supplied);
                for (MediaSubtitle subtitle : subtitles) {
                    if (subtitle == null) throw new IllegalArgumentException();
                    entries.add(new Entry(subtitle));
                }
            }
        } catch (RuntimeException ignored) {
            entries.clear();
            subtitles = null;
        }
        String videoId = null;
        try { if (clock && info != null) videoId = info.getVideoId(); }
        catch (RuntimeException ignored) { /* Source labels do not gate usable current subtitles. */ }
        for (Format format : formats) {
            if (format == null) continue;
            int count = 0;
            CaptionSource source = null;
            for (Entry entry : entries) {
                if (entry.matches(format)) { count++; source = entry.source; }
            }
            NativeCaptionRequest original = NativeCaptionRequest.find(videoId, subtitles, format, factory, visitorCookie, clock);
            mFormats.put(format, new Binding(count, count == 1 ? source : null, original != null));
            if (original != null) mOriginalRequests.put(format, original);
        }
        mRoute = route;
        mDurationMs = durationMs;
    }

    public synchronized void clearSource() {
        mSourceGeneration++;
        clearTrack();
        mFormats.clear();
        mOriginalRequests.clear();
    }

    public CaptionSession getSession() {
        return mSession;
    }

    public CaptionInputSession getInputSession() { return mInputs; }

    /** Safe catalog evidence for this exact native Format; never returns a URL or request context. */
    public synchronized CaptionSource sourceFor(Format format) {
        Binding binding = mClosed ? null : mFormats.get(format);
        return binding == null ? null : binding.source;
    }

    public synchronized void setEnhancementEnabled(boolean enabled) {
        if (mClosed) return;
        mEnhancementEnabled = enabled;
        mInputs.setEnabled(enabled);
        if (enabled) ensureEnhancementInput();
    }

    /** Reuse the last owned sample when AI is enabled after the subtitle was already downloaded. */
    public synchronized void ensureEnhancementInput() {
        CapturedInput input = mLastInput;
        if (!mEnhancementEnabled || input == null || input.bytes == null || !input.analyzed || input.projectionTried ||
                mOwnedExecutor != null && mOwnedExecutor.getQueue().remainingCapacity() == 0 || !current(input.lease, input.revision) || mSession.getSnapshot() != null) return;
        input.projectionTried = true;
        CaptionSession.Sample admission = mSession.beginSample(input.lease.track);
        mInputs.invalidate(input.lease.track);
        schedule(input, admission, true);
    }

    public synchronized void clearTrack() {
        mOutputGeneration++;
        mAwaitingOutputReset = false;
        mLastInput = null;
        mSession.clearTrack();
        mInputs.clear();
        if (mCurrent != null) {
            mCurrent.released = true;
            mCurrent = null;
        }
        if (mOwnedExecutor != null) mOwnedExecutor.getQueue().clear();
    }

    public synchronized void close() {
        clearSource();
        mSession.close();
        mInputs.close();
        mClosed = true;
        if (mOwnedExecutor != null) mOwnedExecutor.shutdownNow();
    }

    @Override
    public boolean supportsFormat(Format format) {
        return mDelegate.supportsFormat(format);
    }

    @Override
    public synchronized SubtitleDecoder createDecoder(Format format) {
        SubtitleDecoder decoder = mDelegate.createDecoder(format);
        Binding binding = mFormats.get(format);
        clearTrack();
        if (mClosed || binding == null || !("text/vtt".equals(format.sampleMimeType) ||
                "application/ttml+xml".equals(format.sampleMimeType))) return decoder;
        mCurrent = new Lease(mSourceGeneration, ++mDecoderId, binding, format.sampleMimeType, mRoute, mDurationMs,
                mSession.selectTrack(binding.candidates, binding.source, binding.currentScope));
        long durationUs = mDurationMs > 0 && mDurationMs <= Long.MAX_VALUE / 1000 ? mDurationMs * 1000 : 0;
        mInputs.bind(mCurrent.track, mOriginalRequests.get(format), durationUs, binding.originalScope);
        return new ObservingSubtitleDecoder(decoder, this, mCurrent);
    }

    synchronized long revision(Lease lease) {
        return current(lease, lease.revision) ? lease.revision : -1;
    }

    synchronized boolean canCapture(Lease lease, long revision) {
        return current(lease, revision);
    }

    synchronized void invalidate(Lease lease, boolean release) {
        if (mCurrent == lease) mAwaitingOutputReset = false;
        lease.revision++;
        lease.released |= release;
        if (mLastInput != null && mLastInput.lease == lease) mLastInput = null;
        if (release) {
            mSession.release(lease.track);
            mInputs.release(lease.track);
        } else mSession.flush(lease.track);
    }

    synchronized void discard(Lease lease, long revision) {
        if (current(lease, revision)) {
            mLastInput = null;
            mSession.discardDocument(lease.track);
            mInputs.invalidate(lease.track);
        }
    }

    /** Invalidate queued native output without discarding the current immutable document/cache. */
    public synchronized void invalidateOutput(boolean awaitingSeek) {
        mOutputGeneration++;
        mAwaitingOutputReset = awaitingSeek && mCurrent != null && current(mCurrent, mCurrent.revision);
    }

    /** Native seek acknowledgement can report a no-op without ever flushing the decoder. */
    public synchronized OutputStamp completeOutputSeek(OutputStamp previous) {
        boolean unchanged = mAwaitingOutputReset && previous != null && previous.mOwner == this &&
                previous.mLease != null && current(previous.mLease, previous.mRevision);
        mAwaitingOutputReset = false;
        return unchanged ? captureOutputStamp() : null;
    }

    public synchronized OutputStamp captureOutputStamp() {
        Lease lease = mCurrent != null && current(mCurrent, mCurrent.revision) ? mCurrent : null;
        return new OutputStamp(this, lease, lease == null ? -1 : lease.revision, mOutputGeneration, !mAwaitingOutputReset);
    }

    /** Native clears may precede decoder.flush/release; their validity must not depend on that revision. */
    public synchronized boolean isOutputValid(OutputStamp stamp) {
        return !mClosed && stamp != null && stamp.mOwner == this && stamp.mOutputGeneration == mOutputGeneration && stamp.mOutputReady;
    }

    public synchronized boolean isOutputCurrent(OutputStamp stamp) {
        return isOutputValid(stamp) && stamp.mLease != null && current(stamp.mLease, stamp.mRevision);
    }

    /** Outermost UI application guard; action must remain short and perform no I/O. */
    public synchronized boolean deliverOutput(OutputStamp stamp, Runnable action) {
        if (action == null) throw new IllegalArgumentException("Invalid caption output action");
        if (!isOutputCurrent(stamp)) return false;
        action.run();
        return true;
    }

    /** Outer completion admission, not a visible-output/window test. A flush can keep the accepted document. */
    private synchronized boolean admitDocument(Lease lease, Runnable action) {
        if (!current(lease, lease.revision)) return false;
        action.run();
        return true;
    }

    private boolean current(Lease lease, long revision) {
        return !mClosed && mCurrent == lease && !lease.released &&
                lease.sourceGeneration == mSourceGeneration && lease.revision == revision;
    }

    void submit(Lease lease, long revision, byte[] bytes, Capture capture,
                long timeUs, long offsetUs, boolean decodeOnly, boolean keyFrame) {
        final CapturedInput input;
        final CaptionSession.Sample admission;
        synchronized (this) {
            if (!current(lease, revision)) return;
            int diagnosticSample = mSink != null && lease.diagnosticSamples < MAX_DIAGNOSTIC_SAMPLES_PER_DECODER ?
                    ++lease.diagnosticSamples : 0;
            input = new CapturedInput(lease, revision, bytes, capture, timeUs, offsetUs, decodeOnly, keyFrame, diagnosticSample);
            // One bounded owned copy lets late AI enable reuse a completed download; never retain a native buffer.
            mLastInput = bytes == null ? null : input;
            admission = mSession.beginSample(lease.track);
            mInputs.invalidate(lease.track);
        }
        schedule(input, admission, false);
    }

    private void schedule(CapturedInput input, CaptionSession.Sample admission, boolean replayed) {
        Lease lease = input.lease;
        long revision = input.revision;
        byte[] bytes = input.bytes;
        Capture capture = input.capture;
        try {
            mExecutor.execute(() -> {
                final boolean project;
                final boolean diagnostic;
                synchronized (CaptionObservation.this) {
                    if (!current(lease, revision)) return;
                    project = mEnhancementEnabled;
                    diagnostic = mSink != null && input.sample > 0 &&
                            lease.diagnosticReports < MAX_DIAGNOSTIC_SAMPLES_PER_DECODER;
                }
                CaptionSampleMetrics.Result metrics = null;
                Capture resultCapture = capture;
                if (diagnostic) {
                    try {
                        if (bytes != null) metrics = CaptionSampleMetrics.inspect(bytes, lease.mime);
                    } catch (RuntimeException ignored) {
                        resultCapture = Capture.ANALYSIS_FAILED;
                    }
                }
                RawTimeline raw = null;
                MappedTranscript transcript = null;
                SemanticGroupPlanner.Plan groups = null;
                TimelineState state = TimelineState.SKIPPED;
                if (bytes != null && "text/vtt".equals(lease.mime)) {
                    try {
                        raw = RawWebvttParser.parse(bytes, input.offsetUs, TIMELINE_LIMITS);
                        AsrTranscriptPlanner.Analysis analysis = AsrTranscriptPlanner.prepare(raw, lease.binding.source,
                                TIMELINE_LIMITS, GROUP_LIMITS);
                        if (analysis.transcript != null && analysis.groups != null) {
                            transcript = analysis.transcript;
                            groups = analysis.groups;
                        } else {
                            transcript = MappedTranscript.fromRaw(raw, TIMELINE_LIMITS.textRuns);
                            groups = SemanticGroupPlanner.preserve(transcript, GROUP_LIMITS);
                        }
                        state = TimelineState.ACCEPTED;
                    } catch (IllegalArgumentException ignored) {
                        raw = null;
                        transcript = null;
                        groups = null;
                        state = TimelineState.REJECTED;
                    }
                }
                if (raw == null && bytes != null && project) {
                    synchronized (CaptionObservation.this) { input.projectionTried = true; }
                    try {
                        long durationUs = lease.durationMs > 0 && lease.durationMs <= Long.MAX_VALUE / 1000 ? lease.durationMs * 1000 : 0;
                        raw = NativeSubtitleProjection.parse(bytes, lease.mime, input.offsetUs, durationUs, TIMELINE_LIMITS);
                        transcript = MappedTranscript.fromRaw(raw, TIMELINE_LIMITS.textRuns);
                        groups = SemanticGroupPlanner.preserve(transcript, GROUP_LIMITS);
                        state = TimelineState.ACCEPTED;
                    } catch (IllegalArgumentException ignored) { raw = null; transcript = null; groups = null; }
                }
                synchronized (CaptionObservation.this) {
                    if (!current(lease, revision)) return;
                    input.analyzed = true;
                    long projectedDurationUs = raw != null && raw.getOrigin() == RawTimeline.Origin.NATIVE_DISPLAY &&
                            lease.durationMs > 0 && lease.durationMs <= Long.MAX_VALUE / 1000 ? lease.durationMs * 1000 : 0;
                    boolean accepted = raw != null && mSession.accept(admission, raw, transcript, groups,
                            new CaptionSession.InputClock(lease.mime, input.offsetUs, projectedDurationUs),
                            action -> admitDocument(lease, action));
                    if (accepted) mInputs.observe(lease.track, mSession.getSnapshot(), input.offsetUs);
                    if (raw == null) mSession.reject(admission);
                    if (diagnostic && lease.diagnosticReports < MAX_DIAGNOSTIC_SAMPLES_PER_DECODER) {
                        lease.diagnosticReports++;
                        try {
                            mSink.accept(new Report(lease, revision, input.sample, input.timeUs, input.offsetUs, input.decodeOnly,
                                    input.keyFrame, resultCapture, metrics, new TimelineSummary(state, raw, transcript, groups), accepted, replayed));
                        } catch (RuntimeException ignored) {
                            // Diagnostics must never turn an accepted native sample into a playback failure.
                        }
                    }
                }
            });
        } catch (RuntimeException ignored) {
            synchronized (this) {
                input.analyzed = true;
                if (replayed) input.projectionTried = false;
            }
            mSession.reject(admission);
            // A full/shut down worker rejects this document; never parse on the renderer/UI caller.
        }
    }
}
