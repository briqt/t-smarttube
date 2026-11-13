package com.liskovsoft.smartyoutubetv2.common.subtitle;

import android.util.Log;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionRuntimeDiagnosticsTest {
    private static final String TAG = "TSmartTubeCaptions";
    private static final String PROBE = "TSmartTubeCaptionProbe";

    @After public void disable() {
        ShadowLog.setLoggable(TAG, Log.INFO);
        ShadowLog.setLoggable(PROBE, Log.INFO);
        ShadowLog.clear();
    }

    @Test public void ordinaryPlaybackHasNeitherLogsNorProbeLimits() {
        ShadowLog.setLoggable(TAG, Log.INFO);
        ShadowLog.setLoggable(PROBE, Log.DEBUG);
        assertFalse(CaptionRuntimeDiagnostics.enabled());
        for (int i = 0; i < 20; i++) assertTrue(CaptionRuntimeDiagnostics.admitProbeExchange());
        CaptionRuntimeDiagnostics.state("bridge stage=inactive");
        assertTrue(ShadowLog.getLogsForTag(TAG).isEmpty());
    }

    @Test public void loggingAloneDoesNotLimitNormalRequests() {
        ShadowLog.setLoggable(TAG, Log.DEBUG);
        ShadowLog.setLoggable(PROBE, Log.INFO);
        for (int i = 0; i < 20; i++) assertTrue(CaptionRuntimeDiagnostics.admitProbeExchange());
        CaptionRuntimeDiagnostics.state("exchange status=RECEIVED http=200 calls=1");
        assertEquals(1, ShadowLog.getLogsForTag(TAG).size());
    }

    @Test public void rejectionNeverLogsExceptionMessagesOrForeignFrames() {
        ShadowLog.setLoggable(TAG, Log.DEBUG);
        IllegalArgumentException failure = new IllegalArgumentException("secret-key https://private.invalid signed-caption-text");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("foreign.secret-key", "private-data", "private-file", 1),
                new StackTraceElement("com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser", "text", "RawWebvttParser.java", 243)
        });
        CaptionRuntimeDiagnostics.rejection("raw", failure);
        assertEquals(1, ShadowLog.getLogsForTag(TAG).size());
        assertEquals("rejected stage=raw method=RawWebvttParser.text line=243", ShadowLog.getLogsForTag(TAG).get(0).msg);
    }

    @Test public void explicitProbeStopsAtFourAndCannotResetByToggling() {
        ShadowLog.setLoggable(TAG, Log.DEBUG);
        ShadowLog.setLoggable(PROBE, Log.DEBUG);
        for (int i = 0; i < 4; i++) assertTrue(CaptionRuntimeDiagnostics.admitProbeExchange());
        assertFalse(CaptionRuntimeDiagnostics.admitProbeExchange());
        ShadowLog.setLoggable(PROBE, Log.INFO);
        assertTrue(CaptionRuntimeDiagnostics.admitProbeExchange());
        ShadowLog.setLoggable(PROBE, Log.DEBUG);
        assertFalse(CaptionRuntimeDiagnostics.admitProbeExchange());
    }
}
