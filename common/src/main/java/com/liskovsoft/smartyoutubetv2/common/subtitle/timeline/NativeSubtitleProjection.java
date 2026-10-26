package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderException;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Whole-sample fallback using public native decoder APIs, not per-screen translation or word timing. */
public final class NativeSubtitleProjection {
    /** Worker-only. Does not borrow the player's pooled buffers, decoder, or network connection. */
    public static RawTimeline parse(byte[] source, String mime, long offsetUs, long durationUs, RawTimeline.Limits limits) {
        if (source == null || limits == null || source.length > limits.documentBytes) throw RawTimeline.invalid();
        byte[] snapshot = source.clone();
        String document = RawTimeline.decodeUtf8(snapshot, limits);
        if (document.indexOf('\0') >= 0 || !("text/vtt".equals(mime) || "application/ttml+xml".equals(mime))) throw RawTimeline.invalid();
        preflight(document, mime);
        SubtitleDecoder decoder = null;
        SubtitleOutputBuffer output = null;
        try {
            decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(Format.createTextSampleFormat(null, mime, 0, null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            SubtitleInputBuffer input;
            while ((input = decoder.dequeueInputBuffer()) == null) pause(deadline);
            input.ensureSpaceForWrite(snapshot.length);
            input.data.put(snapshot);
            input.timeUs = 0;
            input.subsampleOffsetUs = offsetUs;
            input.addFlag(C.BUFFER_FLAG_KEY_FRAME);
            input.flip();
            decoder.queueInputBuffer(input);
            while ((output = decoder.dequeueOutputBuffer()) == null) pause(deadline);
            int events = output.getEventTimeCount();
            if (events > limits.cues * 2 + 1) throw RawTimeline.invalid();
            List<RawTimeline.Cue> cues = new ArrayList<>();
            int characters = 0;
            for (int i = 0; i < events; i++) {
                long start = output.getEventTime(i);
                long end = i + 1 < events ? output.getEventTime(i + 1) : durationUs;
                if (durationUs > 0) end = Math.min(end, durationUs);
                if (end <= start) continue;
                List<com.google.android.exoplayer2.text.Cue> visible = output.getCues(start);
                if (visible.isEmpty()) continue;
                // This fork's SubtitleView displays only the last cue, not a made-up merged track.
                com.google.android.exoplayer2.text.Cue cue = visible.get(visible.size() - 1);
                if (cue.bitmap != null || cue.text == null || cue.text.length() == 0) continue;
                String text = cue.text.toString();
                if (cues.size() >= limits.cues || cues.size() >= limits.textRuns || text.length() > limits.documentBytes - characters) throw RawTimeline.invalid();
                characters += text.length();
                List<RawTimeline.TextRun> runs = Collections.singletonList(new RawTimeline.TextRun(0, text.length(),
                        start, end, start, end, RawTimeline.TimingKind.NATIVE_DISPLAY_INTERVAL, false));
                cues.add(new RawTimeline.Cue(i, null, start, end, text, runs, false));
            }
            return new RawTimeline(snapshot, cues, limits, Collections.emptyMap(), RawTimeline.Origin.NATIVE_DISPLAY);
        } catch (SubtitleDecoderException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            throw RawTimeline.invalid();
        } finally {
            if (output != null) output.release();
            if (decoder != null) decoder.release();
        }
    }

    private static void pause(long deadline) throws InterruptedException {
        if (System.nanoTime() >= deadline) throw RawTimeline.invalid();
        Thread.sleep(1); // Native decoder is asynchronous; this bounded wait never runs on the renderer.
    }

    private static void preflight(String document, String mime) {
        if ("text/vtt".equals(mime)) {
            if (!(document.startsWith("WEBVTT") || document.startsWith("﻿WEBVTT"))) throw RawTimeline.invalid();
            int tags = 0;
            for (int i = 0; i < document.length(); i++) if (document.charAt(i) == '<' && ++tags > 8192) throw RawTimeline.invalid();
            return;
        }
        String lower = document.toLowerCase(Locale.US);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) throw RawTimeline.invalid();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new StringReader(document));
            int nodes = 0;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (++nodes > 8192 || parser.getDepth() > 32 ||
                        parser.getEventType() == XmlPullParser.START_TAG && parser.getAttributeCount() > 64) throw RawTimeline.invalid();
            }
        } catch (Exception ignored) { throw RawTimeline.invalid(); }
    }

    private NativeSubtitleProjection() { }
}
