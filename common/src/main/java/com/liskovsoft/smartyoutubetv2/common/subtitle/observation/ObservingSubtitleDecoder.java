package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderException;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;

import java.nio.ByteBuffer;

/** Transparent native ownership/decode path; only bounded, independent copies go to diagnostics. */
final class ObservingSubtitleDecoder implements SubtitleDecoder {
    private final SubtitleDecoder mDelegate;
    private final CaptionObservation mObservation;
    private final CaptionObservation.Lease mLease;

    ObservingSubtitleDecoder(SubtitleDecoder delegate, CaptionObservation observation, CaptionObservation.Lease lease) {
        mDelegate = delegate;
        mObservation = observation;
        mLease = lease;
    }

    @Override
    public String getName() {
        return mDelegate.getName();
    }

    @Override
    public SubtitleInputBuffer dequeueInputBuffer() throws SubtitleDecoderException {
        return mDelegate.dequeueInputBuffer();
    }

    @Override
    public void queueInputBuffer(SubtitleInputBuffer input) throws SubtitleDecoderException {
        long revision = mObservation.revision(mLease);
        byte[] bytes = null;
        CaptionObservation.Capture capture = CaptionObservation.Capture.UNSUPPORTED_BUFFER;
        long timeUs = 0;
        long offsetUs = 0;
        boolean decodeOnly = false;
        boolean keyFrame = false;
        boolean observe = revision >= 0 && mObservation.canCapture(mLease, revision) &&
                input != null && !input.isEndOfStream() && !input.isEncrypted();
        boolean discard = !observe && input != null && !input.isEndOfStream();
        if (observe) {
            timeUs = input.timeUs;
            offsetUs = input.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE ? timeUs : input.subsampleOffsetUs;
            decodeOnly = input.isDecodeOnly();
            keyFrame = input.isKeyFrame();
            try {
                ByteBuffer data = input.data;
                if (data != null && data.hasArray() && data.arrayOffset() == 0 && data.limit() > 0) {
                    if (data.limit() > CaptionSampleMetrics.MAX_BYTES) {
                        capture = CaptionObservation.Capture.TOO_LARGE;
                    } else {
                        bytes = new byte[data.limit()];
                        ByteBuffer copy = data.duplicate();
                        copy.position(0);
                        copy.get(bytes); // SimpleSubtitleDecoder reads array[0, limit), not remaining().
                        capture = CaptionObservation.Capture.CAPTURED;
                    }
                }
            } catch (RuntimeException ignored) {
                bytes = null;
            }
        }
        mDelegate.queueInputBuffer(input);
        if (observe) {
            mObservation.submit(mLease, revision, bytes, capture, timeUs, offsetUs, decodeOnly, keyFrame);
        } else if (discard) {
            mObservation.discard(mLease, revision);
        }
    }

    @Override
    public SubtitleOutputBuffer dequeueOutputBuffer() throws SubtitleDecoderException {
        return mDelegate.dequeueOutputBuffer();
    }

    @Override
    public void setPositionUs(long positionUs) {
        mDelegate.setPositionUs(positionUs);
    }

    @Override
    public void flush() {
        mObservation.invalidate(mLease, false);
        mDelegate.flush();
    }

    @Override
    public void release() {
        mObservation.invalidate(mLease, true);
        mDelegate.release();
    }
}
