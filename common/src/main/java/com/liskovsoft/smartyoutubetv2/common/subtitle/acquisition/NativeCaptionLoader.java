package com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition;

import com.google.android.exoplayer2.upstream.DataSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.NativeSubtitleProjection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.AsrTranscriptPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Independent bounded original-caption reads through the player's DataSource, never a bare HTTP client. */
public final class NativeCaptionLoader {
    public enum Status { ACCEPTED, REJECTED, ERROR, CANCELLED }
    public interface Callback { void complete(Result result); }
    public static final class Result {
        public final Status status;
        public final RawTimeline timeline;
        public final MappedTranscript transcript;
        public final SemanticGroupPlanner.Plan groups;
        private Result(Status status, RawTimeline timeline, MappedTranscript transcript, SemanticGroupPlanner.Plan groups) {
            this.status = status;
            this.timeline = timeline;
            this.transcript = transcript;
            this.groups = groups;
        }
    }

    public final class Load {
        private final NativeCaptionRequest mRequest;
        private final long mDurationUs;
        private final RawTimeline.Limits mLimits;
        private final Callback mCallback;
        private DataSource mSource;
        private boolean mOpened;
        private boolean mCancelled;
        private boolean mDelivered;
        private final Runnable mTask = this::run;

        private Load(NativeCaptionRequest request, long durationUs, RawTimeline.Limits limits, Callback callback) {
            mRequest = request;
            mDurationUs = durationUs;
            mLimits = limits;
            mCallback = callback;
        }

        /** Never performs network close/IO on the caller (renderer/UI) thread. */
        public void cancel() {
            DataSource source;
            synchronized (this) {
                mCancelled = true;
                source = mOpened ? mSource : null;
            }
            if (mWorker instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) mWorker).remove(mTask);
            if (source != null) {
                try { mCloser.execute(() -> safeClose(source)); }
                catch (RuntimeException ignored) { /* Reader still closes after its native transport timeout. */ }
            }
            complete(Status.CANCELLED, null);
        }

        private synchronized boolean cancelled() { return mCancelled; }

        private void run() {
            DataSource source = null;
            RawTimeline timeline = null;
            Status status = Status.ERROR;
            try {
                if (cancelled()) { complete(Status.CANCELLED, null); return; }
                source = mRequest.createDataSource();
                synchronized (this) { mSource = source; }
                if (cancelled()) { complete(Status.CANCELLED, null); return; }
                long length = source.open(mRequest.dataSpec());
                synchronized (this) { mOpened = true; }
                if (length > mLimits.documentBytes) throw new Rejected();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int emptyReads = 0;
                while (!cancelled()) {
                    int count = source.read(buffer, 0, Math.min(buffer.length, mLimits.documentBytes - bytes.size() + 1));
                    if (count == -1) break;
                    if (count == 0) { if (++emptyReads > 16) throw new IOException(); continue; }
                    if (count > mLimits.documentBytes - bytes.size()) throw new Rejected();
                    emptyReads = 0;
                    bytes.write(buffer, 0, count);
                }
                if (cancelled()) { status = Status.CANCELLED; }
                else {
                    byte[] document = bytes.toByteArray();
                    try {
                        if (!"text/vtt".equals(mRequest.mime())) throw new IllegalArgumentException();
                        timeline = RawWebvttParser.parse(document, 0, mLimits);
                    } catch (IllegalArgumentException ignored) {
                        if (new String(document, Charset.forName("UTF-8")).contains("X-TIMESTAMP-MAP")) throw new Rejected();
                        timeline = NativeSubtitleProjection.parse(document, mRequest.mime(), 0, mDurationUs, mLimits);
                    }
                    status = Status.ACCEPTED;
                }
            } catch (Rejected | IllegalArgumentException ignored) { status = Status.REJECTED; }
            catch (IOException | RuntimeException ignored) { status = Status.ERROR; }
            finally {
                synchronized (this) { mSource = null; mOpened = false; }
                if (source != null) safeClose(source);
            }
            complete(status, timeline);
        }

        private void complete(Status status, RawTimeline timeline) {
            MappedTranscript transcript = null;
            SemanticGroupPlanner.Plan groups = null;
            if (status == Status.ACCEPTED && timeline != null) {
                try {
                    SemanticGroupPlanner.Limits groupLimits =
                            new SemanticGroupPlanner.Limits(mLimits.cues, mLimits.textRuns, mLimits.textRuns);
                    AsrTranscriptPlanner.Analysis analysis = AsrTranscriptPlanner.prepare(timeline, mRequest.source, mLimits, groupLimits);
                    if (analysis.transcript != null && analysis.groups != null) {
                        transcript = analysis.transcript;
                        groups = analysis.groups;
                    } else {
                        transcript = MappedTranscript.fromRaw(timeline, mLimits.textRuns);
                        groups = SemanticGroupPlanner.preserve(transcript, groupLimits);
                    }
                } catch (IllegalArgumentException ignored) { status = Status.REJECTED; timeline = null; transcript = null; groups = null; }
            }
            synchronized (this) {
                if (mDelivered) return;
                mDelivered = true;
                if (mCancelled) { status = Status.CANCELLED; timeline = null; transcript = null; groups = null; }
            }
            synchronized (NativeCaptionLoader.this) { mLoads.remove(this); }
            try { mCallback.complete(new Result(status, timeline, transcript, groups)); }
            catch (RuntimeException ignored) { /* Isolate diagnostics/consumer failures from playback. */ }
        }
    }

    private final Set<Load> mLoads = Collections.newSetFromMap(new IdentityHashMap<Load, Boolean>());
    private boolean mClosed;
    private final Executor mWorker;
    private final Executor mCloser;
    private final ThreadPoolExecutor mOwnedWorker;
    private final ThreadPoolExecutor mOwnedCloser;

    public NativeCaptionLoader() {
        this(worker("caption-original"), worker("caption-original-close"));
    }

    private NativeCaptionLoader(ThreadPoolExecutor worker, ThreadPoolExecutor closer) {
        mWorker = worker;
        mCloser = closer;
        mOwnedWorker = worker;
        mOwnedCloser = closer;
    }

    NativeCaptionLoader(Executor worker, Executor closer) {
        mWorker = worker;
        mCloser = closer;
        mOwnedWorker = null;
        mOwnedCloser = null;
    }

    public Load start(NativeCaptionRequest request, long durationUs, RawTimeline.Limits limits, Callback callback) {
        if (request == null || durationUs <= 0 || limits == null || callback == null) throw new IllegalArgumentException("Invalid caption load");
        Load load = new Load(request, durationUs, limits, callback);
        boolean closed;
        synchronized (this) { closed = mClosed; if (!closed) mLoads.add(load); }
        if (closed) { load.complete(Status.CANCELLED, null); return load; }
        try { mWorker.execute(load.mTask); }
        catch (RuntimeException ignored) { load.complete(Status.ERROR, null); }
        return load;
    }

    public void close() {
        ArrayList<Load> loads;
        synchronized (this) { if (mClosed) return; mClosed = true; loads = new ArrayList<>(mLoads); }
        for (Load load : loads) load.cancel();
        if (mOwnedWorker != null) mOwnedWorker.shutdownNow();
        if (mOwnedCloser != null) mOwnedCloser.shutdown();
    }

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                runnable -> new Thread(runnable, name), new ThreadPoolExecutor.AbortPolicy());
    }

    private static void safeClose(DataSource source) {
        try { synchronized (source) { source.close(); } }
        catch (IOException | RuntimeException ignored) { }
    }

    private static final class Rejected extends Exception { }
}
