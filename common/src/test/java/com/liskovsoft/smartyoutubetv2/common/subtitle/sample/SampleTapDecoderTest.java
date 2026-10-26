package com.liskovsoft.smartyoutubetv2.common.subtitle.sample;

import android.app.Application;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderException;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.smartyoutubetv2.common.subtitle.sample.SampleTapDecoder.CapturedSample;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = Config.NONE, application = Application.class)
public class SampleTapDecoderTest {
    private static final int CAPTURE_LIMIT = 4096;
    private static final String VTT = "WEBVTT\n\n00:00.000 --> 00:01.000\nFirst\n\n" +
            "00:12.000 --> 00:13.000\nLast\n";
    private static final String TTML = "<tt xmlns=\"http://www.w3.org/ns/ttml\">" +
            "<body begin=\"1s\"><div begin=\"0s\"><p begin=\"2s\" dur=\"1s\">First</p>" +
            "<p begin=\"4s\" dur=\"1s\">Last</p></div></body></tt>";

    @Test
    public void copiesArrayPrefixNotRemainingAndPreservesInputState() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, captured);
        SubtitleInputBuffer input = input("complete document");
        input.data.position(1);
        input.data.mark();
        input.data.position(4);
        delegate.onQueue = buffer -> {
            int position = buffer.data.position();
            buffer.data.reset();
            delegate.observedMark = buffer.data.position();
            buffer.data.position(position);
        };
        tap.queueInputBuffer(input);
        assertEquals(4, delegate.observedPosition);
        assertEquals(bytes("complete document").length, delegate.observedLimit);
        assertEquals(1, delegate.observedMark);
        assertArrayEquals(bytes("complete document"), captured.get(0).copyData());
        assertSame(input, delegate.queuedInput);
    }

    @Test
    public void doesNotCopySpareCapacity() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SubtitleInputBuffer input = input("doc plus stale tail");
        input.data.limit(3);
        tap(delegate, captured).queueInputBuffer(input);
        assertArrayEquals(bytes("doc"), captured.get(0).copyData());
        assertEquals(3, delegate.observedLimit);
    }

    @Test
    public void copiesMetadataBeforeDelegateCanRecycleInput() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SubtitleInputBuffer input = input("original bytes");
        input.timeUs = 7_000_001L;
        input.subsampleOffsetUs = -123L;
        input.setFlags(C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_DECODE_ONLY);
        delegate.onQueue = buffer -> {
            buffer.clear();
            buffer.data.put(bytesUnchecked("reused"));
            buffer.timeUs = 99;
            buffer.subsampleOffsetUs = 100;
        };
        tap(delegate, captured).queueInputBuffer(input);
        CapturedSample sample = captured.get(0);
        assertArrayEquals(bytes("original bytes"), sample.copyData());
        assertEquals(7_000_001L, sample.getTimeUs());
        assertEquals(-123L, sample.getSubsampleOffsetUs());
        assertTrue(sample.isDecodeOnly());
        assertTrue(sample.isKeyFrame());
        assertTrue(delegate.observedDecodeOnly);
        assertTrue(delegate.observedKeyFrame);
    }

    @Test
    public void returnedBytesCannotMutateTheSnapshot() throws Exception {
        List<CapturedSample> captured = new ArrayList<>();
        tap(new FakeDecoder(), captured).queueInputBuffer(input("原文🙂"));
        byte[] copy = captured.get(0).copyData();
        copy[0] = 0;
        assertArrayEquals(bytes("原文🙂"), captured.get(0).copyData());
    }

    @Test
    public void eosIsForwardedWithoutPayloadAndDoesNotInvalidateTimeline() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, captured);
        tap.queueInputBuffer(input("document"));
        SubtitleInputBuffer eos = input("stale payload");
        eos.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
        tap.queueInputBuffer(eos);
        assertSame(eos, delegate.queuedInput);
        assertEquals(2, delegate.queueCalls);
        assertEquals(1, captured.size());
        assertTrue(tap.isCurrent(captured.get(0)));
    }

    @Test
    public void nullEmptyAndEncryptedDataAreNotCaptured() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, captured);
        tap.queueInputBuffer(new SubtitleInputBuffer());
        tap.queueInputBuffer(input(""));
        SubtitleInputBuffer encrypted = input("ciphertext fixture");
        encrypted.setFlags(C.BUFFER_FLAG_ENCRYPTED);
        tap.queueInputBuffer(encrypted);
        assertEquals(3, delegate.queueCalls);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void captureBoundIsInclusiveAndDoesNotTruncateNativeInput() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = new SampleTapDecoder(delegate, MimeTypes.TEXT_VTT, 3, captured::add);
        tap.queueInputBuffer(input("abc"));
        SubtitleInputBuffer oversized = input("abcd");
        tap.queueInputBuffer(oversized);
        assertEquals(2, delegate.queueCalls);
        assertEquals(4, delegate.observedLimit);
        assertSame(oversized, delegate.queuedInput);
        assertEquals(1, captured.size());
        assertArrayEquals(bytes("abc"), captured.get(0).copyData());
    }

    @Test
    public void unsupportedBufferLayoutsRemainUnmodifiedAndUncaptured() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, captured);
        ByteBuffer backing = ByteBuffer.wrap(bytes("prefix payload"));
        backing.position(7);
        ByteBuffer slice = backing.slice();
        ByteBuffer direct = ByteBuffer.allocateDirect(3);
        direct.put(bytes("doc"));
        direct.flip();
        ByteBuffer readOnly = ByteBuffer.wrap(bytes("doc")).asReadOnlyBuffer();
        for (ByteBuffer data : new ByteBuffer[]{slice, direct, readOnly}) {
            SubtitleInputBuffer input = new SubtitleInputBuffer();
            input.data = data;
            tap.queueInputBuffer(input);
            assertSame(data, delegate.queuedInput.data);
        }
        assertEquals(3, delegate.queueCalls);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void unsupportedMimeTypesRemainNativeOnly() throws Exception {
        for (String mimeType : new String[]{MimeTypes.APPLICATION_SUBRIP, MimeTypes.APPLICATION_PGS, null}) {
            FakeDecoder delegate = new FakeDecoder();
            List<CapturedSample> captured = new ArrayList<>();
            SampleTapDecoder tap = new SampleTapDecoder(delegate, mimeType, CAPTURE_LIMIT, captured::add);
            SubtitleInputBuffer input = input("opaque sample");
            tap.queueInputBuffer(input);
            assertSame(input, delegate.queuedInput);
            assertTrue(captured.isEmpty());
        }
    }

    @Test
    public void delegatesNameAvailabilityPositionAndBufferIdentity() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        SampleTapDecoder tap = tap(delegate, new ArrayList<>());
        assertEquals("fake-subtitle-decoder", tap.getName());
        assertSame(delegate.input, tap.dequeueInputBuffer());
        assertSame(delegate.output, tap.dequeueOutputBuffer());
        tap.setPositionUs(9_876_543_210L);
        assertEquals(9_876_543_210L, delegate.positionUs);
        assertEquals(1, delegate.positionCalls);
        delegate.input = null;
        delegate.output = null;
        assertNull(tap.dequeueInputBuffer());
        assertNull(tap.dequeueOutputBuffer());
    }

    @Test
    public void delegateExceptionsKeepTheirIdentityAndFailedQueueHasNoCapture() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, captured);
        SubtitleDecoderException expected = new SubtitleDecoderException("delegate fixture");
        delegate.inputError = expected;
        try {
            tap.dequeueInputBuffer();
            fail("Expected original input exception");
        } catch (SubtitleDecoderException actual) {
            assertSame(expected, actual);
        }
        delegate.queueError = expected;
        try {
            tap.queueInputBuffer(input("document"));
            fail("Expected original queue exception");
        } catch (SubtitleDecoderException actual) {
            assertSame(expected, actual);
        }
        assertTrue(captured.isEmpty());
        delegate.outputError = expected;
        try {
            tap.dequeueOutputBuffer();
            fail("Expected original output exception");
        } catch (SubtitleDecoderException actual) {
            assertSame(expected, actual);
        }
    }

    @Test
    public void observerFailureDoesNotTurnSuccessfulQueueIntoPlaybackFailure() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        int[] callbacks = {0};
        SampleTapDecoder tap = new SampleTapDecoder(delegate, MimeTypes.TEXT_VTT, CAPTURE_LIMIT, sample -> {
            callbacks[0]++;
            throw new IllegalStateException("handoff fixture");
        });
        tap.queueInputBuffer(input("document"));
        assertEquals(1, callbacks[0]);
        assertEquals(1, delegate.queueCalls);
    }

    @Test
    public void flushInvalidatesDelayedWorkButNotOwnedBytes() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> pending = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, pending);
        tap.queueInputBuffer(input("old"));
        CapturedSample old = pending.get(0);
        tap.flush();
        tap.queueInputBuffer(input("new"));
        List<String> accepted = new ArrayList<>();
        for (CapturedSample sample : pending) {
            if (tap.isCurrent(sample)) {
                accepted.add(new String(sample.copyData(), "UTF-8"));
            }
        }
        assertEquals(Arrays.asList("new"), accepted);
        assertArrayEquals(bytes("old"), old.copyData());
        assertEquals(1, delegate.flushCalls);
    }

    @Test
    public void releaseAndLaterFlushCannotReviveCapture() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(delegate, captured);
        tap.queueInputBuffer(input("old"));
        tap.release();
        tap.flush();
        tap.queueInputBuffer(input("new"));
        assertFalse(tap.isCurrent(captured.get(0)));
        assertEquals(1, captured.size());
        assertEquals(1, delegate.releaseCalls);
        assertEquals(1, delegate.flushCalls);
        assertEquals(2, delegate.queueCalls);
    }

    @Test
    public void snapshotsDoNotBelongToAnotherDecoderWithTheSameGeneration() throws Exception {
        List<CapturedSample> first = new ArrayList<>();
        List<CapturedSample> second = new ArrayList<>();
        SampleTapDecoder a = tap(new FakeDecoder(), first);
        SampleTapDecoder b = tap(new FakeDecoder(), second);
        a.queueInputBuffer(input("a"));
        b.queueInputBuffer(input("b"));
        assertFalse(a.isCurrent(second.get(0)));
        assertFalse(b.isCurrent(first.get(0)));
        assertTrue(a.isCurrent(first.get(0)));
    }

    @Test
    public void lifecycleChangeInsideDelegateQueueSuppressesOldHandoff() throws Exception {
        for (boolean release : new boolean[]{false, true}) {
            FakeDecoder delegate = new FakeDecoder();
            List<CapturedSample> captured = new ArrayList<>();
            SampleTapDecoder tap = tap(delegate, captured);
            delegate.onQueue = buffer -> {
                if (release) {
                    tap.release();
                } else {
                    tap.flush();
                }
            };
            tap.queueInputBuffer(input("old"));
            assertTrue(captured.isEmpty());
            assertEquals(1, delegate.queueCalls);
        }
    }

    @Test
    public void lifecycleFailureStillInvalidatesOldWorkAndPropagates() throws Exception {
        for (boolean release : new boolean[]{false, true}) {
            FakeDecoder delegate = new FakeDecoder();
            List<CapturedSample> captured = new ArrayList<>();
            SampleTapDecoder tap = tap(delegate, captured);
            tap.queueInputBuffer(input("old"));
            RuntimeException expected = new IllegalStateException("lifecycle fixture");
            delegate.flushError = expected;
            delegate.releaseError = expected;
            try {
                if (release) {
                    tap.release();
                } else {
                    tap.flush();
                }
                fail("Expected original lifecycle exception");
            } catch (RuntimeException actual) {
                assertSame(expected, actual);
            }
            assertFalse(tap.isCurrent(captured.get(0)));
        }
    }

    @Test
    public void outputReleaseRemainsTheCallersResponsibility() throws Exception {
        FakeDecoder delegate = new FakeDecoder();
        SampleTapDecoder tap = tap(delegate, new ArrayList<>());
        TestOutput output = (TestOutput) tap.dequeueOutputBuffer();
        tap.flush();
        assertEquals(0, output.releaseCalls);
        output.release();
        tap.release();
        assertEquals(1, output.releaseCalls);
    }

    @Test
    public void preservesExplicitAndSampleRelativeMicrosecondOffsets() throws Exception {
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = tap(new FakeDecoder(), captured);
        SubtitleInputBuffer relative = input("relative");
        relative.timeUs = 7_000_001L;
        relative.subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE;
        tap.queueInputBuffer(relative);
        SubtitleInputBuffer explicit = input("explicit");
        explicit.timeUs = C.TIME_UNSET;
        explicit.subsampleOffsetUs = -456L;
        tap.queueInputBuffer(explicit);
        assertEquals(Format.OFFSET_SAMPLE_RELATIVE, captured.get(0).getSubsampleOffsetUs());
        assertEquals(7_000_001L, captured.get(0).getEffectiveOffsetUs());
        assertEquals(C.TIME_UNSET, captured.get(1).getTimeUs());
        assertEquals(-456L, captured.get(1).getEffectiveOffsetUs());
    }

    @Test
    public void boundedPositionLimitFixturesPreserveTheNativePrefix() throws Exception {
        Random random = new Random(19);
        for (int i = 0; i < 100; i++) {
            FakeDecoder delegate = new FakeDecoder();
            List<CapturedSample> captured = new ArrayList<>();
            byte[] data = new byte[32];
            random.nextBytes(data);
            int limit = random.nextInt(33);
            int position = random.nextInt(limit + 1);
            SubtitleInputBuffer input = new SubtitleInputBuffer();
            input.data = ByteBuffer.wrap(data);
            input.data.limit(limit);
            input.data.position(position);
            new SampleTapDecoder(delegate, MimeTypes.TEXT_VTT, 32, captured::add).queueInputBuffer(input);
            assertEquals(position, delegate.observedPosition);
            assertEquals(limit, delegate.observedLimit);
            if (limit == 0) {
                assertTrue(captured.isEmpty());
            } else {
                assertArrayEquals(Arrays.copyOf(data, limit), captured.get(0).copyData());
            }
        }
    }

    @Test
    public void realVttQueueMatchesNativeOutputAndCopiesWholeDocument() throws Exception {
        NativeResult plain = roundTrip(MimeTypes.TEXT_VTT, VTT, 2_000_000L, Format.OFFSET_SAMPLE_RELATIVE, false);
        NativeResult tapped = roundTrip(MimeTypes.TEXT_VTT, VTT, 2_000_000L, Format.OFFSET_SAMPLE_RELATIVE, true);
        assertArrayEquals(new long[]{2_000_000L, 3_000_000L, 14_000_000L, 15_000_000L}, tapped.times);
        assertArrayEquals(plain.times, tapped.times);
        assertEquals(plain.texts, tapped.texts);
        assertEquals(Arrays.asList("First", "Last"), tapped.texts);
        assertArrayEquals(bytes(VTT), tapped.sample.copyData());
        assertEquals(MimeTypes.TEXT_VTT, tapped.sample.getMimeType());
    }

    @Test
    public void realTtmlQueuePreservesParentTimingAndExplicitOffset() throws Exception {
        NativeResult plain = roundTrip(MimeTypes.APPLICATION_TTML, TTML, 7_000_000L, 11_000_000L, false);
        NativeResult tapped = roundTrip(MimeTypes.APPLICATION_TTML, TTML, 7_000_000L, 11_000_000L, true);
        assertArrayEquals(new long[]{14_000_000L, 15_000_000L, 16_000_000L, 17_000_000L}, tapped.times);
        assertArrayEquals(plain.times, tapped.times);
        assertEquals(plain.texts, tapped.texts);
        assertEquals(Arrays.asList("First", "Last"), tapped.texts);
        assertArrayEquals(bytes(TTML), tapped.sample.copyData());
        assertEquals(MimeTypes.APPLICATION_TTML, tapped.sample.getMimeType());
        assertEquals(11_000_000L, tapped.sample.getEffectiveOffsetUs());
    }

    @Test
    public void untimedIntermediateNodeRetainsTheNativeAncestorTimingLimitation() throws Exception {
        String untimedDiv = TTML.replace("<div begin=\"0s\">", "<div>");
        NativeResult explicitParent = roundTrip(MimeTypes.APPLICATION_TTML, TTML,
                7_000_000L, 11_000_000L, false);
        NativeResult nativeUntimed = roundTrip(MimeTypes.APPLICATION_TTML, untimedDiv,
                7_000_000L, 11_000_000L, false);
        NativeResult tappedUntimed = roundTrip(MimeTypes.APPLICATION_TTML, untimedDiv,
                7_000_000L, 11_000_000L, true);
        // Characterize the existing decoder; do not treat its projection as the raw timeline.
        assertArrayEquals(new long[]{14_000_000L, 15_000_000L, 16_000_000L, 17_000_000L}, explicitParent.times);
        assertArrayEquals(new long[]{13_000_000L, 14_000_000L, 15_000_000L, 16_000_000L}, nativeUntimed.times);
        assertArrayEquals(nativeUntimed.times, tappedUntimed.times);
        assertEquals(nativeUntimed.texts, tappedUntimed.texts);
        assertEquals(Arrays.asList("First", "Last"), tappedUntimed.texts);
        assertArrayEquals(bytes(untimedDiv), tappedUntimed.sample.copyData());
    }

    @Test
    public void acceptedQueueDoesNotHideALaterNativeDecodeFailure() throws Exception {
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = nativeTap(MimeTypes.TEXT_VTT, captured);
        SubtitleOutputBuffer output = null;
        try {
            queueDocument(tap, "not a WebVTT document", 0, 0);
            assertEquals(1, captured.size());
            try {
                output = awaitOutput(tap, 0);
                fail("Expected the native parser to reject the fixture");
            } catch (SubtitleDecoderException expected) {
                try {
                    tap.dequeueOutputBuffer();
                    fail("Expected the same stored native failure");
                } catch (SubtitleDecoderException actual) {
                    assertSame(expected, actual);
                }
            }
            // A current snapshot is not proof that its bytes form a valid subtitle document.
            assertTrue(tap.isCurrent(captured.get(0)));
        } finally {
            if (output != null) {
                output.release();
            }
            tap.release();
        }
    }

    @Test
    public void realDecoderFlushDropsOldOutputAndOldCaptureGeneration() throws Exception {
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = nativeTap(MimeTypes.TEXT_VTT, captured);
        SubtitleOutputBuffer output = null;
        try {
            queueDocument(tap, VTT.replace("First", "Old"), 0, 0);
            tap.flush();
            queueDocument(tap, VTT.replace("First", "New"), 0, 0);
            output = awaitOutput(tap, 500_000L);
            assertEquals("New", output.getCues(500_000L).get(0).text.toString());
            assertEquals(2, captured.size());
            assertFalse(tap.isCurrent(captured.get(0)));
            assertTrue(tap.isCurrent(captured.get(1)));
        } finally {
            if (output != null) {
                output.release();
            }
            tap.release();
        }
    }

    @Test
    public void realDecoderEosDoesNotBecomeAnExtraDocumentOrDiscardCapture() throws Exception {
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = nativeTap(MimeTypes.TEXT_VTT, captured);
        SubtitleOutputBuffer output = null;
        try {
            queueDocument(tap, VTT, 0, 0);
            SubtitleInputBuffer eos = tap.dequeueInputBuffer();
            assertNotNull(eos);
            eos.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
            tap.queueInputBuffer(eos);
            output = awaitOutput(tap, 500_000L);
            assertFalse(output.isEndOfStream());
            assertEquals("First", output.getCues(500_000L).get(0).text.toString());
            output.release();
            output = null;
            output = awaitOutput(tap, 500_000L);
            assertTrue(output.isEndOfStream());
            assertEquals(1, captured.size());
            assertTrue(tap.isCurrent(captured.get(0)));
        } finally {
            if (output != null) {
                output.release();
            }
            tap.release();
        }
    }

    private static NativeResult roundTrip(String mimeType, String document, long timeUs,
                                          long offsetUs, boolean decorate) throws Exception {
        Format format = Format.createTextSampleFormat(null, mimeType, 0, null);
        assertTrue(SubtitleDecoderFactory.DEFAULT.supportsFormat(format));
        SubtitleDecoder nativeDecoder = SubtitleDecoderFactory.DEFAULT.createDecoder(format);
        List<CapturedSample> captured = new ArrayList<>();
        SampleTapDecoder tap = decorate ? new SampleTapDecoder(nativeDecoder, mimeType, CAPTURE_LIMIT, captured::add) : null;
        SubtitleDecoder decoder = tap != null ? tap : nativeDecoder;
        SubtitleOutputBuffer output = null;
        NativeResult result;
        try {
            queueDocument(decoder, document, timeUs, offsetUs);
            output = awaitOutput(decoder, timeUs);
            assertFalse(output.isEndOfStream());
            long[] times = new long[output.getEventTimeCount()];
            for (int i = 0; i < times.length; i++) {
                times[i] = output.getEventTime(i);
            }
            List<String> texts = Arrays.asList(output.getCues(times[0]).get(0).text.toString(),
                    output.getCues(times[times.length - 2]).get(0).text.toString());
            result = new NativeResult(times, texts, captured.isEmpty() ? null : captured.get(0));
            if (tap != null) {
                assertEquals(1, captured.size());
                assertTrue(tap.isCurrent(result.sample));
            }
        } finally {
            if (output != null) {
                output.release();
            }
            decoder.release();
        }
        if (tap != null) {
            assertFalse(tap.isCurrent(result.sample));
        }
        return result;
    }

    private static SampleTapDecoder nativeTap(String mimeType, List<CapturedSample> captured) {
        Format format = Format.createTextSampleFormat(null, mimeType, 0, null);
        return new SampleTapDecoder(SubtitleDecoderFactory.DEFAULT.createDecoder(format), mimeType,
                CAPTURE_LIMIT, captured::add);
    }

    private static void queueDocument(SubtitleDecoder decoder, String document, long timeUs,
                                      long offsetUs) throws Exception {
        SubtitleInputBuffer input = decoder.dequeueInputBuffer();
        assertNotNull(input);
        byte[] data = bytes(document);
        input.ensureSpaceForWrite(data.length);
        input.data.put(data);
        input.timeUs = timeUs;
        input.subsampleOffsetUs = offsetUs;
        input.setFlags(C.BUFFER_FLAG_KEY_FRAME);
        input.flip();
        decoder.queueInputBuffer(input);
    }

    private static SubtitleOutputBuffer awaitOutput(SubtitleDecoder decoder, long positionUs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            decoder.setPositionUs(positionUs);
            SubtitleOutputBuffer output = decoder.dequeueOutputBuffer();
            if (output != null) {
                return output;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Native subtitle output timed out");
    }

    private static SampleTapDecoder tap(FakeDecoder delegate, List<CapturedSample> captured) {
        return new SampleTapDecoder(delegate, MimeTypes.TEXT_VTT, CAPTURE_LIMIT, captured::add);
    }

    private static SubtitleInputBuffer input(String text) throws Exception {
        SubtitleInputBuffer input = new SubtitleInputBuffer();
        byte[] data = bytes(text);
        input.ensureSpaceForWrite(data.length);
        input.data.put(data);
        input.flip();
        return input;
    }

    private static byte[] bytes(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    private static byte[] bytesUnchecked(String text) {
        try {
            return bytes(text);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class NativeResult {
        final long[] times;
        final List<String> texts;
        final CapturedSample sample;

        NativeResult(long[] times, List<String> texts, CapturedSample sample) {
            this.times = times;
            this.texts = texts;
            this.sample = sample;
        }
    }

    private static final class TestOutput extends SubtitleOutputBuffer {
        int releaseCalls;

        @Override
        public void release() {
            releaseCalls++;
        }
    }

    private interface BufferAction {
        void run(SubtitleInputBuffer input);
    }

    private static final class FakeDecoder implements SubtitleDecoder {
        SubtitleInputBuffer input = new SubtitleInputBuffer();
        SubtitleOutputBuffer output = new TestOutput();
        SubtitleInputBuffer queuedInput;
        SubtitleDecoderException inputError;
        SubtitleDecoderException queueError;
        SubtitleDecoderException outputError;
        RuntimeException flushError;
        RuntimeException releaseError;
        BufferAction onQueue;
        int queueCalls;
        int flushCalls;
        int releaseCalls;
        int positionCalls;
        long positionUs;
        int observedPosition;
        int observedLimit;
        int observedMark;
        boolean observedDecodeOnly;
        boolean observedKeyFrame;

        @Override
        public String getName() {
            return "fake-subtitle-decoder";
        }

        @Override
        public SubtitleInputBuffer dequeueInputBuffer() throws SubtitleDecoderException {
            if (inputError != null) {
                throw inputError;
            }
            return input;
        }

        @Override
        public void queueInputBuffer(SubtitleInputBuffer input) throws SubtitleDecoderException {
            queueCalls++;
            queuedInput = input;
            observedPosition = input.data != null ? input.data.position() : -1;
            observedLimit = input.data != null ? input.data.limit() : -1;
            observedDecodeOnly = input.isDecodeOnly();
            observedKeyFrame = input.isKeyFrame();
            if (queueError != null) {
                throw queueError;
            }
            if (onQueue != null) {
                onQueue.run(input);
            }
        }

        @Override
        public SubtitleOutputBuffer dequeueOutputBuffer() throws SubtitleDecoderException {
            if (outputError != null) {
                throw outputError;
            }
            return output;
        }

        @Override
        public void setPositionUs(long positionUs) {
            this.positionUs = positionUs;
            positionCalls++;
        }

        @Override
        public void flush() {
            flushCalls++;
            if (flushError != null) {
                throw flushError;
            }
        }

        @Override
        public void release() {
            releaseCalls++;
            if (releaseError != null) {
                throw releaseError;
            }
        }
    }
}
