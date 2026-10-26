package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Source-mapped text. Local atom IDs and ranges are not persistent translation identities. */
public final class MappedTranscript {
    public static final class Origin {
        public final int cueIndex;
        public final int sourceIndex;
        public final int runIndex;
        public final int textStart;
        public final int textEnd;
        public final long activeStartUs;
        public final long activeEndUs;
        public final long timingStartUs;
        public final long timingEndUs;
        public final RawTimeline.TimingKind timingKind;
        public final boolean lineBreak;
        public final boolean echo;
        public final boolean wholeRun;

        Origin(int cueIndex, RawTimeline.Cue cue, int runIndex) {
            this(cueIndex, cue, runIndex, cue.runs.get(runIndex).textStart, cue.runs.get(runIndex).textEnd, false);
        }

        Origin(int cueIndex, RawTimeline.Cue cue, int runIndex, int start, int end, boolean echo) {
            RawTimeline.TextRun run = cue.runs.get(runIndex);
            if (start < run.textStart || end > run.textEnd || end <= start ||
                    !boundary(cue.mappingText, start) || !boundary(cue.mappingText, end)) throw RawTimeline.invalid();
            this.cueIndex = cueIndex;
            sourceIndex = cue.sourceIndex;
            this.runIndex = runIndex;
            textStart = start;
            textEnd = end;
            activeStartUs = run.activeStartUs;
            activeEndUs = run.activeEndUs;
            timingStartUs = run.timingStartUs;
            timingEndUs = run.timingEndUs;
            timingKind = run.timingKind;
            lineBreak = run.lineBreak;
            this.echo = echo;
            wholeRun = start == run.textStart && end == run.textEnd;
        }
    }

    public static final class Atom {
        public final int index;
        public final int flowIndex;
        public final String text;
        public final List<Origin> origins;

        Atom(int index, int flowIndex, String text, List<Origin> origins) {
            this.index = index;
            this.flowIndex = flowIndex;
            this.text = text;
            this.origins = Collections.unmodifiableList(new ArrayList<>(origins));
        }
    }

    /** UTF-16 range into one atom, possibly only part of a coarse timed text fragment. */
    public static final class Slice {
        public final int atomIndex;
        public final int start;
        public final int end;

        public Slice(int atomIndex, int start, int end) {
            if (atomIndex < 0 || start < 0 || end <= start) {
                throw RawTimeline.invalid();
            }
            this.atomIndex = atomIndex;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Reference {
        public final Origin origin;
        public final int textStart;
        public final int textEnd;
        public final boolean wholeRun;

        Reference(Origin origin, int textStart, int textEnd, boolean wholeRun) {
            this.origin = origin;
            this.textStart = textStart;
            this.textEnd = textEnd;
            this.wholeRun = wholeRun;
        }
    }

    private final RawTimeline mSource;
    private final List<Atom> mAtoms;
    private final boolean mResolvedRanges;

    /** Preserve every run and independent cue; no rolling/continuity or word timing is inferred. */
    public static MappedTranscript fromRaw(RawTimeline raw, int maxAtoms) {
        if (raw == null || maxAtoms <= 0 || maxAtoms > 65536) throw RawTimeline.invalid();
        List<Atom> atoms = new ArrayList<>();
        for (int cueIndex = 0; cueIndex < raw.getCues().size(); cueIndex++) {
            RawTimeline.Cue cue = raw.getCues().get(cueIndex);
            for (int runIndex = 0; runIndex < cue.runs.size(); runIndex++) {
                if (atoms.size() >= maxAtoms) throw RawTimeline.invalid();
                RawTimeline.TextRun run = cue.runs.get(runIndex);
                atoms.add(new Atom(atoms.size(), cueIndex,
                        cue.mappingText.substring(run.textStart, run.textEnd),
                        Collections.singletonList(new Origin(cueIndex, cue, runIndex))));
            }
        }
        return new MappedTranscript(raw, atoms, maxAtoms);
    }

    // Historical offline constructor for the test-only rolling/segmentation contracts.
    MappedTranscript(RawTimeline raw, List<Atom> atoms) {
        this(raw, atoms, RawTimeline.MAX_TEXT_RUNS);
    }

    private MappedTranscript(RawTimeline raw, List<Atom> atoms, int maxAtoms) {
        this(raw, atoms, maxAtoms, false);
    }

    /** Only the bounded production resolver supplies partial origins and explicit display echoes. */
    static MappedTranscript resolved(RawTimeline raw, List<Atom> atoms, int maxAtoms) {
        return new MappedTranscript(raw, atoms, maxAtoms, true);
    }

    private MappedTranscript(RawTimeline raw, List<Atom> atoms, int maxAtoms, boolean resolved) {
        if (raw == null || atoms == null || maxAtoms <= 0 || maxAtoms > 65536 || atoms.size() > maxAtoms) {
            throw RawTimeline.invalid();
        }
        boolean[][] covered = new boolean[raw.getCues().size()][];
        for (int i = 0; i < covered.length; i++) {
            RawTimeline.Cue cue = raw.getCues().get(i);
            covered[i] = new boolean[resolved ? cue.mappingText.length() : cue.runs.size()];
        }
        int previousFlow = -1, references = 0;
        for (int i = 0; i < atoms.size(); i++) {
            Atom atom = atoms.get(i);
            if (atom == null || atom.index != i || atom.flowIndex < previousFlow || atom.flowIndex < 0 ||
                    atom.text == null || atom.text.isEmpty() || atom.origins.isEmpty() || atom.origins.size() > 65536 - references) {
                throw RawTimeline.invalid();
            }
            references += atom.origins.size();
            previousFlow = atom.flowIndex;
            for (int originIndex = 0; originIndex < atom.origins.size(); originIndex++) {
                Origin origin = atom.origins.get(originIndex);
                if (origin == null || origin.cueIndex < 0 || origin.cueIndex >= covered.length) throw RawTimeline.invalid();
                RawTimeline.Cue cue = raw.getCues().get(origin.cueIndex);
                if (origin.runIndex < 0 || origin.runIndex >= cue.runs.size() || cue.sourceIndex != origin.sourceIndex) {
                    throw RawTimeline.invalid();
                }
                RawTimeline.TextRun run = cue.runs.get(origin.runIndex);
                if (origin.textStart < run.textStart || origin.textEnd > run.textEnd ||
                        !atom.text.equals(cue.mappingText.substring(origin.textStart, origin.textEnd)) ||
                        !boundary(cue.mappingText, origin.textStart) || !boundary(cue.mappingText, origin.textEnd) ||
                        run.activeStartUs != origin.activeStartUs || run.activeEndUs != origin.activeEndUs ||
                        run.timingStartUs != origin.timingStartUs || run.timingEndUs != origin.timingEndUs ||
                        run.timingKind != origin.timingKind || run.lineBreak != origin.lineBreak ||
                        (resolved ? origin.echo != (originIndex > 0) : !origin.wholeRun || origin.echo)) {
                    throw RawTimeline.invalid();
                }
                int start = resolved ? origin.textStart : origin.runIndex;
                int end = resolved ? origin.textEnd : start + 1;
                for (int offset = start; offset < end; offset++) {
                    if (covered[origin.cueIndex][offset]) throw RawTimeline.invalid();
                    covered[origin.cueIndex][offset] = true;
                }
            }
        }
        for (boolean[] cue : covered) for (boolean point : cue) if (!point) throw RawTimeline.invalid();
        mSource = raw;
        mAtoms = Collections.unmodifiableList(new ArrayList<>(atoms));
        mResolvedRanges = resolved;
    }

    /** Identity check prevents attaching references from an equal-looking different document. */
    public boolean isFor(RawTimeline raw) {
        return mSource == raw;
    }

    public List<Atom> getAtoms() {
        return mAtoms;
    }

    /** Distinguishes source-range/echo resolution from the historical whole-run mapping contract. */
    public int normalizationVersion() { return mResolvedRanges ? 1 : 0; }

    public String text(Slice slice) {
        Atom atom = checked(slice);
        return atom.text.substring(slice.start, slice.end);
    }

    /** References keep the source timing window; partial text does not acquire a new word clock. */
    public List<Reference> references(Slice slice) {
        Atom atom = checked(slice);
        List<Reference> references = new ArrayList<>();
        for (Origin origin : atom.origins) {
            references.add(new Reference(origin, origin.textStart + slice.start,
                    origin.textStart + slice.end, origin.wholeRun && slice.start == 0 && slice.end == atom.text.length()));
        }
        return Collections.unmodifiableList(references);
    }

    private Atom checked(Slice slice) {
        if (slice == null || slice.atomIndex >= mAtoms.size()) {
            throw RawTimeline.invalid();
        }
        Atom atom = mAtoms.get(slice.atomIndex);
        if (slice.end > atom.text.length() || !boundary(atom.text, slice.start) || !boundary(atom.text, slice.end)) {
            throw RawTimeline.invalid();
        }
        return atom;
    }

    static boolean boundary(String text, int offset) {
        return offset == 0 || offset == text.length() ||
                !(Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)));
    }
}
