package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionObservationNativeTest {
    @Test
    public void realVttAndTtmlPoolQueueOutputRemainEquivalentWithObservation() throws Exception {
        String[] mimes = {"text/vtt", "application/ttml+xml"};
        String[] samples = {"WEBVTT\n\n00:01.000 --> 00:14.000\nsynthetic\n",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1s\" end=\"14s\">synthetic</p></div></body></tt>"};
        for (int i = 0; i < mimes.length; i++) {
            assertEquals(project(false, mimes[i], samples[i]), project(true, mimes[i], samples[i]));
        }
    }

    @Test
    public void finalApplicationDebugFlagControlsDiagnosticsNotTheBusinessInstance() throws Exception {
        Context base = RuntimeEnvironment.getApplication();
        for (int flags : new int[]{0, ApplicationInfo.FLAG_DEBUGGABLE}) {
            Context context = new ContextWrapper(base) {
                @Override public ApplicationInfo getApplicationInfo() {
                    ApplicationInfo info = new ApplicationInfo();
                    info.flags = flags;
                    return info;
                }
            };
            ShadowLog.clear();
            CaptionObservation instance = CaptionObservation.create(context);
            assertNotNull("Non-debug applications must retain the production caption bridge", instance);
            SubtitleDecoder decoder = null;
            try {
                Format format = CaptionObservationTest.format("a", "English", "text/vtt");
                instance.bindSource(CaptionObservationTest.info(CaptionObservationTest.subtitle("a", "English", "text/vtt", "en", null)),
                        CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
                decoder = instance.createDecoder(format);
                assertTrue(decoder instanceof ObservingSubtitleDecoder);
                SubtitleInputBuffer input = decoder.dequeueInputBuffer();
                assertNotNull(input);
                byte[] bytes = "WEBVTT\n\n00:01.000 --> 00:04.000\nproduction document\n".getBytes(Charset.forName("UTF-8"));
                input.ensureSpaceForWrite(bytes.length);
                input.data.put(bytes);
                input.subsampleOffsetUs = 0;
                input.flip();
                decoder.queueInputBuffer(input);
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (instance.getSession().getSnapshot() == null && System.nanoTime() < deadline) Thread.sleep(2);
                assertNotNull(instance.getSession().getSnapshot());
                assertEquals("production document", instance.getSession().getSnapshot().groups.getText(0));
                assertNull(instance.getInputSession().tick(new Object()));
                assertEquals(flags, context.getApplicationInfo().flags);
            } finally {
                if (decoder != null) decoder.release();
                instance.close();
            }
            if (flags == 0) assertTrue(ShadowLog.getLogsForTag("CaptionObservation").isEmpty());
            else assertFalse(ShadowLog.getLogsForTag("CaptionObservation").isEmpty());
        }
    }

    private List<String> project(boolean observed, String mime, String sample) throws Exception {
        Format format = CaptionObservationTest.format("a", "English", mime);
        List<CaptionObservation.Report> reports = new ArrayList<>();
        CaptionObservation observation = new CaptionObservation(SubtitleDecoderFactory.DEFAULT, Runnable::run, reports::add);
        observation.bindSource(CaptionObservationTest.info(CaptionObservationTest.subtitle("a", "English", mime, "en", null)),
                CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
        SubtitleDecoder decoder = observed ? observation.createDecoder(format) : SubtitleDecoderFactory.DEFAULT.createDecoder(format);
        SubtitleOutputBuffer output = null;
        try {
            SubtitleInputBuffer input = decoder.dequeueInputBuffer();
            assertNotNull(input);
            byte[] bytes = sample.getBytes(Charset.forName("UTF-8"));
            input.ensureSpaceForWrite(bytes.length);
            input.data.put(bytes);
            input.timeUs = 3_000_000;
            input.subsampleOffsetUs = 4_000_000;
            input.flip();
            decoder.queueInputBuffer(input);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (output == null && System.nanoTime() < deadline) {
                output = decoder.dequeueOutputBuffer();
                if (output == null) Thread.sleep(2);
            }
            assertNotNull(output);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < output.getEventTimeCount(); i++) result.add("t=" + output.getEventTime(i));
            for (long time : new long[]{4_000_000, 5_500_000, 16_000_000, 19_000_000}) {
                result.add("c=" + output.getCues(time).size());
                output.getCues(time).forEach(cue -> result.add(String.valueOf(cue.text)));
            }
            assertEquals(observed ? 1 : 0, reports.size());
            return result;
        } finally {
            if (output != null) output.release();
            decoder.release();
            observation.close();
        }
    }
}
