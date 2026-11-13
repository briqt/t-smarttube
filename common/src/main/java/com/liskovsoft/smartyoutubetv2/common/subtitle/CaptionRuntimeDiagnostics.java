package com.liskovsoft.smartyoutubetv2.common.subtitle;

import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅显式 ADB 开启；只记录内部标量与代码位置，绝不记录正文、地址、凭据或异常消息。 */
public final class CaptionRuntimeDiagnostics {
    private static final String TAG = "TSmartTubeCaptions";
    private static final String PROBE_TAG = "TSmartTubeCaptionProbe";
    private static final AtomicInteger PROBE_CALLS = new AtomicInteger();
    private static final int PROBE_LIMIT = 4;

    private CaptionRuntimeDiagnostics() { }

    public static boolean enabled() {
        try { return Log.isLoggable(TAG, Log.DEBUG); }
        catch (RuntimeException unavailable) { return false; }
    }

    public static void state(String safeState) {
        if (!enabled()) return;
        try { Log.d(TAG, safeState); }
        catch (RuntimeException ignored) { /* 诊断不得影响播放。 */ }
    }

    public static void rejection(String stage, RuntimeException error) {
        if (!enabled()) return;
        if (error instanceof com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser.TimestampRejection) {
            com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser.TimestampRejection t =
                    (com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser.TimestampRejection) error;
            state("timestamp cue=" + t.cueIndex + " start=" + t.cueStartUs + " previous=" + t.previousUs +
                    " current=" + t.currentUs + " end=" + t.cueEndUs + " chars=" + t.runCharacters);
        }
        int count = 0;
        for (StackTraceElement frame : error.getStackTrace()) {
            if (!frame.getClassName().startsWith("com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.") ||
                    "invalid".equals(frame.getMethodName())) continue;
            state("rejected stage=" + stage + " method=" + frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) +
                    "." + frame.getMethodName() + " line=" + frame.getLineNumber());
            if (++count == 3) break;
        }
    }

    /** 额外的探针开关才限流；普通播放默认不进入此路径，计数不在进程内重置。 */
    public static boolean admitProbeExchange() {
        if (!enabled() || !Log.isLoggable(PROBE_TAG, Log.DEBUG)) return true;
        int call = PROBE_CALLS.incrementAndGet();
        boolean admitted = call <= PROBE_LIMIT;
        state("probe admitted=" + admitted + " reserved=" + Math.min(call, PROBE_LIMIT));
        return admitted;
    }
}
