package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import android.os.Handler;
import android.os.Looper;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.text.TextRenderer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stamp before the renderer-to-UI queue, then forward all original cues on the original output looper. */
public final class CaptionCueDispatcher implements TextOutput {
    private final CaptionObservation mObservation;
    private final TextOutput mOutput;
    private final Handler mHandler;

    public CaptionCueDispatcher(CaptionObservation observation, TextOutput output, Looper outputLooper) {
        if (observation == null || output == null) throw new IllegalArgumentException("Invalid caption output");
        mObservation = observation;
        mOutput = output;
        mHandler = outputLooper == null ? null : new Handler(outputLooper);
    }

    public static TextRenderer createRenderer(CaptionObservation observation, TextOutput output, Looper outputLooper) {
        // Native TextRenderer explicitly supports direct renderer-thread callbacks with a null looper.
        // Our dispatcher restores the same UI queue after capturing the exact decoder/revision stamp.
        return new TextRenderer(new CaptionCueDispatcher(observation, output, outputLooper), null, observation);
    }

    @Override
    public void onCues(List<Cue> cues) {
        List<Cue> stamped = new Stamped(cues, mObservation.captureOutputStamp());
        if (mHandler == null) mOutput.onCues(stamped);
        else mHandler.post(() -> mOutput.onCues(stamped));
    }

    public static CaptionObservation.OutputStamp stampOf(List<Cue> cues) {
        return cues instanceof Stamped ? ((Stamped) cues).stamp : null;
    }

    private static final class Stamped extends AbstractList<Cue> {
        final CaptionObservation.OutputStamp stamp;
        final List<Cue> cues;
        Stamped(List<Cue> cues, CaptionObservation.OutputStamp stamp) {
            this.cues = cues == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(cues));
            this.stamp = stamp;
        }
        @Override public Cue get(int index) { return cues.get(index); }
        @Override public int size() { return cues.size(); }
    }
}
