package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** In-memory source text and timing. Local ordinals/ranges are not stable track or cache identities. */
public final class RawTimeline {
    // Historical offline profile. Live callers must supply explicit limits instead.
    static final int MAX_DOCUMENT_BYTES = 64 * 1024;
    static final int MAX_CUES = 256;
    static final int MAX_TEXT_RUNS = 2048;
    static final Limits OFFLINE_LIMITS = new Limits(MAX_DOCUMENT_BYTES, MAX_CUES, MAX_TEXT_RUNS);

    public static final class Limits {
        public final int documentBytes;
        public final int cues;
        public final int textRuns;

        /** Admission budgets, not a declaration of complete format coverage or product defaults. */
        public Limits(int documentBytes, int cues, int textRuns) {
            if (documentBytes <= 0 || documentBytes > 1024 * 1024 || cues <= 0 || cues > 8192 ||
                    textRuns <= 0 || textRuns > 65536) {
                throw invalid();
            }
            this.documentBytes = documentBytes;
            this.cues = cues;
            this.textRuns = textRuns;
        }
    }

    public enum Origin { DOCUMENT, NATIVE_DISPLAY }

    public enum TimingKind {
        CUE_INTERVAL,
        TTML_INTERVAL,
        VTT_TIMESTAMP_WINDOW,
        NATIVE_DISPLAY_INTERVAL
    }

    /** A source fragment, not necessarily one lexical word or a measured speech duration. */
    public static final class TextRun {
        public final int textStart;
        public final int textEnd;
        public final long activeStartUs;
        public final long activeEndUs;
        public final long timingStartUs;
        public final long timingEndUs;
        public final TimingKind timingKind;
        public final boolean lineBreak;

        TextRun(int textStart, int textEnd, long activeStartUs, long activeEndUs,
                long timingStartUs, long timingEndUs, TimingKind timingKind, boolean lineBreak) {
            if (textStart < 0 || textEnd <= textStart || activeEndUs <= activeStartUs ||
                    timingStartUs < activeStartUs || timingEndUs > activeEndUs ||
                    timingEndUs <= timingStartUs || timingKind == null) {
                throw invalid();
            }
            this.textStart = textStart;
            this.textEnd = textEnd;
            this.activeStartUs = activeStartUs;
            this.activeEndUs = activeEndUs;
            this.timingStartUs = timingStartUs;
            this.timingEndUs = timingEndUs;
            this.timingKind = timingKind;
            this.lineBreak = lineBreak;
        }
    }

    public static final class Cue {
        public final int sourceIndex;
        public final String sourceId;
        public final long startUs;
        public final long endUs;
        public final String text;
        public final String mappingText;
        public final List<TextRun> runs;
        /** Frozen WebVTT display metadata, not source text or a replacement for native cue layout. */
        public final Map<String, String> webvttSettings;
        private final boolean mXmlWhitespace;

        Cue(int sourceIndex, String sourceId, long startUs, long endUs, String text) {
            this(sourceIndex, sourceId, startUs, endUs, text, wholeCue(text, startUs, endUs), false);
        }

        Cue(int sourceIndex, String sourceId, long startUs, long endUs, String text,
            Map<String, String> webvttSettings) {
            this(sourceIndex, sourceId, startUs, endUs, text, wholeCue(text, startUs, endUs), false, webvttSettings);
        }

        Cue(int sourceIndex, String sourceId, long startUs, long endUs, String mappingText,
            List<TextRun> runs, boolean xmlWhitespace) {
            this(sourceIndex, sourceId, startUs, endUs, mappingText, runs, xmlWhitespace, Collections.emptyMap());
        }

        Cue(int sourceIndex, String sourceId, long startUs, long endUs, String mappingText,
            List<TextRun> runs, boolean xmlWhitespace, Map<String, String> webvttSettings) {
            if (sourceIndex < 0 || endUs <= startUs || mappingText == null || runs == null ||
                    runs.size() > 65536 || webvttSettings == null || webvttSettings.size() > 4) {
                throw invalid();
            }
            int cursor = 0;
            for (TextRun run : runs) {
                if (run == null || run.textStart != cursor || run.textEnd > mappingText.length() ||
                        run.activeStartUs < startUs || run.activeEndUs > endUs ||
                        !boundary(mappingText, run.textEnd) ||
                        (run.lineBreak && (run.textEnd != run.textStart + 1 ||
                                mappingText.charAt(run.textStart) != '\n'))) {
                    throw invalid();
                }
                cursor = run.textEnd;
            }
            if (cursor != mappingText.length()) {
                throw invalid();
            }
            this.sourceIndex = sourceIndex;
            this.sourceId = sourceId;
            this.startUs = startUs;
            this.endUs = endUs;
            this.mappingText = mappingText;
            this.runs = Collections.unmodifiableList(new ArrayList<>(runs));
            for (Map.Entry<String, String> setting : webvttSettings.entrySet()) {
                if (setting.getKey() == null || setting.getValue() == null || setting.getKey().isEmpty() ||
                        setting.getKey().length() > 16 || setting.getValue().isEmpty() || setting.getValue().length() > 32) {
                    throw invalid();
                }
            }
            this.webvttSettings = Collections.unmodifiableMap(new LinkedHashMap<>(webvttSettings));
            mXmlWhitespace = xmlWhitespace;
            text = render(0, true);
        }

        /** Display projection only. VTT timestamp windows do not hide past or future text. */
        public String textAt(long timeUs) {
            return timeUs < startUs || timeUs >= endUs ? "" : render(timeUs, false);
        }

        private String render(long timeUs, boolean all) {
            StringBuilder result = new StringBuilder();
            boolean pendingSpace = false;
            for (TextRun run : runs) {
                if (!all && (timeUs < run.activeStartUs || timeUs >= run.activeEndUs)) {
                    continue;
                }
                if (!mXmlWhitespace) {
                    result.append(mappingText, run.textStart, run.textEnd);
                } else if (run.lineBreak) {
                    pendingSpace = false;
                    result.append('\n');
                } else {
                    for (int i = run.textStart; i < run.textEnd; i++) {
                        char value = mappingText.charAt(i);
                        if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
                            pendingSpace = true;
                        } else {
                            if (pendingSpace && result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
                                result.append(' ');
                            }
                            pendingSpace = false;
                            result.append(value);
                        }
                    }
                }
            }
            return result.toString();
        }

        private static List<TextRun> wholeCue(String text, long startUs, long endUs) {
            if (text == null) {
                throw invalid();
            }
            return text.isEmpty() ? Collections.emptyList() : Collections.singletonList(
                    new TextRun(0, text.length(), startUs, endUs, startUs, endUs, TimingKind.CUE_INTERVAL, false));
        }

        private static boolean boundary(String text, int index) {
            return index == 0 || index == text.length() ||
                    !(Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index)));
        }
    }

    private final byte[] mSource;
    private final List<Cue> mCues;
    private final Map<String, String> mMetadata;
    private final Origin mOrigin;

    RawTimeline(byte[] source, List<Cue> cues) {
        this(source, cues, OFFLINE_LIMITS);
    }

    RawTimeline(byte[] source, List<Cue> cues, Limits limits) {
        this(source, cues, limits, Collections.emptyMap());
    }

    RawTimeline(byte[] source, List<Cue> cues, Limits limits, Map<String, String> metadata) {
        this(source, cues, limits, metadata, Origin.DOCUMENT);
    }

    RawTimeline(byte[] source, List<Cue> cues, Limits limits, Map<String, String> metadata, Origin origin) {
        if (source == null || limits == null || metadata == null || origin == null || source.length > limits.documentBytes ||
                cues == null || cues.size() > limits.cues) {
            throw invalid();
        }
        int runs = 0;
        for (Cue cue : cues) {
            if (cue == null) throw invalid();
            runs += cue.runs.size();
            if (runs > limits.textRuns) {
                throw invalid();
            }
        }
        mSource = source.clone();
        mCues = Collections.unmodifiableList(new ArrayList<>(cues));
        mMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        mOrigin = origin;
    }

    /** Native projection is fallback display timing, never a claim of original cue/word boundaries. */
    public Origin getOrigin() { return mOrigin; }

    /** Informational headers only, not verified language/provenance or a track-selection policy. */
    public Map<String, String> getMetadata() {
        return mMetadata;
    }

    /** Contains original caption data. Do not write it to diagnostics or ordinary logs. */
    public byte[] copySource() {
        return mSource.clone();
    }

    public List<Cue> getCues() {
        return mCues;
    }

    /** Half-open intervals in document order; overlaps and repeated text remain independent. */
    public List<Cue> getCuesAt(long timeUs) {
        List<Cue> active = new ArrayList<>();
        for (Cue cue : mCues) {
            if (cue.startUs <= timeUs && timeUs < cue.endUs) active.add(cue);
        }
        return Collections.unmodifiableList(active);
    }

    static String decodeUtf8(byte[] source) {
        return decodeUtf8(source, OFFLINE_LIMITS);
    }

    static String decodeUtf8(byte[] source, Limits limits) {
        if (source == null || limits == null || source.length == 0 || source.length > limits.documentBytes) {
            throw invalid();
        }
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source)).toString();
        } catch (CharacterCodingException e) {
            throw invalid();
        }
    }

    static long add(long first, long second) {
        long sum = first + second;
        if (((first ^ sum) & (second ^ sum)) < 0) throw invalid();
        return sum;
    }

    static IllegalArgumentException invalid() {
        // Do not attach documents, signed URLs, or source-bearing parser exception causes.
        return new IllegalArgumentException("Unsupported or invalid subtitle document");
    }
}
