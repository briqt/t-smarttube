package com.liskovsoft.smartyoutubetv2.common.subtitle.sample;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderException;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;

import java.nio.ByteBuffer;

/** Test-only prototype; not registered with the player or a renderer factory. */
final class SampleTapDecoder implements SubtitleDecoder {
    interface Listener {
        void onSample(CapturedSample sample);
    }

    static final class CapturedSample {
        private final byte[] mData;
        private final String mMimeType;
        private final long mTimeUs;
        private final long mSubsampleOffsetUs;
        private final boolean mDecodeOnly;
        private final boolean mKeyFrame;
        private final Object mOwner;
        private final long mGeneration;

        private CapturedSample(byte[] data, String mimeType, long timeUs, long subsampleOffsetUs,
                               boolean decodeOnly, boolean keyFrame, Object owner, long generation) {
            mData = data;
            mMimeType = mimeType;
            mTimeUs = timeUs;
            mSubsampleOffsetUs = subsampleOffsetUs;
            mDecodeOnly = decodeOnly;
            mKeyFrame = keyFrame;
            mOwner = owner;
            mGeneration = generation;
        }

        byte[] copyData() {
            return mData.clone();
        }

        String getMimeType() {
            return mMimeType;
        }

        long getTimeUs() {
            return mTimeUs;
        }

        long getSubsampleOffsetUs() {
            return mSubsampleOffsetUs;
        }

        boolean isDecodeOnly() {
            return mDecodeOnly;
        }

        boolean isKeyFrame() {
            return mKeyFrame;
        }

        long getEffectiveOffsetUs() {
            return mSubsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE ? mTimeUs : mSubsampleOffsetUs;
        }
    }

    private final SubtitleDecoder mDelegate;
    private final String mMimeType;
    private final int mMaxCaptureBytes;
    private final Listener mListener;
    private final Object mOwner = new Object();
    private volatile long mGeneration;
    private volatile boolean mReleased;

    SampleTapDecoder(SubtitleDecoder delegate, String mimeType, int maxCaptureBytes, Listener listener) {
        if (delegate == null || listener == null || maxCaptureBytes <= 0) {
            throw new IllegalArgumentException("Delegate, listener and positive capture limit required");
        }
        mDelegate = delegate;
        mMimeType = mimeType;
        mMaxCaptureBytes = maxCaptureBytes;
        mListener = listener;
    }

    boolean isCurrent(CapturedSample sample) {
        return sample != null && sample.mOwner == mOwner && !mReleased && sample.mGeneration == mGeneration;
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
    public void queueInputBuffer(SubtitleInputBuffer inputBuffer) throws SubtitleDecoderException {
        CapturedSample sample = capture(inputBuffer);
        mDelegate.queueInputBuffer(inputBuffer);
        if (isCurrent(sample)) {
            try {
                mListener.onSample(sample);
            } catch (RuntimeException ignored) {
                // A failed handoff must not turn a successful native queue into a playback error.
            }
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
        mGeneration++;
        mDelegate.flush();
    }

    @Override
    public void release() {
        mReleased = true;
        mGeneration++;
        mDelegate.release();
    }

    private CapturedSample capture(SubtitleInputBuffer inputBuffer) {
        long generation = mGeneration;
        if (mReleased || inputBuffer == null || inputBuffer.isEndOfStream() || inputBuffer.isEncrypted() ||
                !(MimeTypes.TEXT_VTT.equals(mMimeType) || MimeTypes.APPLICATION_TTML.equals(mMimeType))) {
            return null;
        }
        try {
            ByteBuffer data = inputBuffer.data;
            // Match SimpleSubtitleDecoder's array[0, limit) contract, not remaining().
            if (data == null || !data.hasArray() || data.arrayOffset() != 0 ||
                    data.limit() == 0 || data.limit() > mMaxCaptureBytes) {
                return null;
            }
            byte[] bytes = new byte[data.limit()];
            ByteBuffer copy = data.duplicate();
            copy.position(0);
            copy.get(bytes);
            return new CapturedSample(bytes, mMimeType, inputBuffer.timeUs, inputBuffer.subsampleOffsetUs,
                    inputBuffer.isDecodeOnly(), inputBuffer.isKeyFrame(), mOwner, generation);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
