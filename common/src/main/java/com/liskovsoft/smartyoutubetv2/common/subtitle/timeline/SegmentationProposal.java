package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed source-preserving proposal validation; no provider, lexical-word guarantee, or new timing. */
public final class SegmentationProposal {
    public enum TextPolicy {
        EXACT,
        /** Explicit caller choice for English fixtures; no language inference or ASR word repair. */
        ENGLISH_PUNCTUATION_AND_CASE
    }

    public enum Completion {
        COMPLETE, TRUNCATED, FAILED, UNKNOWN
    }

    public enum Problem {
        NONE, INCOMPLETE_COMPLETION, MISSING_OUTPUT, INVALID_REFERENCE, INCOMPLETE, CROSS_FLOW, TEXT_CHANGED, OVER_BUDGET
    }

    public static final class Piece {
        public final String id;
        public final MappedTranscript.Slice slice;
        public final String text;
        public final int flowIndex;

        private Piece(int ordinal, MappedTranscript.Slice slice, String text, int flowIndex) {
            id = "p" + ordinal;
            this.slice = slice;
            this.text = text;
            this.flowIndex = flowIndex;
        }
    }

    public static final class Unit {
        public final List<String> sourceRefs;
        public final String suggestedSource;

        public Unit(List<String> sourceRefs, String suggestedSource) {
            if (sourceRefs != null && sourceRefs.size() > 65536 ||
                    suggestedSource != null && suggestedSource.length() > 2 * 1024 * 1024) throw RawTimeline.invalid();
            this.sourceRefs = sourceRefs == null ? null : Collections.unmodifiableList(new ArrayList<>(sourceRefs));
            this.suggestedSource = suggestedSource;
        }
    }

    public static final class AcceptedUnit {
        public final List<Piece> pieces;
        public final String originalText;
        public final String suggestedSource;

        private AcceptedUnit(List<Piece> pieces, String originalText, String suggestedSource) {
            this.pieces = Collections.unmodifiableList(new ArrayList<>(pieces));
            this.originalText = originalText;
            this.suggestedSource = suggestedSource;
        }
    }

    public static final class Decision {
        public final Problem problem;
        public final List<AcceptedUnit> units;
        private final SegmentationProposal mOwner;

        private Decision(SegmentationProposal owner, Problem problem, List<AcceptedUnit> units) {
            mOwner = owner;
            this.problem = problem;
            this.units = Collections.unmodifiableList(new ArrayList<>(units));
        }

        public boolean accepted() {
            return problem == Problem.NONE;
        }
    }

    public static final class Limits {
        public final int pieces;
        public final int references;
        public final int outputCodePoints;
        public final int units;

        /** Explicit local admission/output budgets; not token counts or translation/display defaults. */
        public Limits(int pieces, int references, int outputCodePoints, int units) {
            if (pieces <= 0 || pieces > 65536 || references <= 0 || references > 65536 ||
                    outputCodePoints <= 0 || outputCodePoints > 1024 * 1024 || units <= 0 || units > 65536) {
                throw RawTimeline.invalid();
            }
            this.pieces = pieces;
            this.references = references;
            this.outputCodePoints = outputCodePoints;
            this.units = units;
        }
    }

    /** Whitespace-aware bounded pieces, not measured words. No language/ASR inference is performed. */
    public static SegmentationProposal prepare(MappedTranscript transcript, int maxPieceCodePoints, Limits limits) {
        if (transcript == null || limits == null || maxPieceCodePoints <= 0 || maxPieceCodePoints > 1024 * 1024) {
            throw RawTimeline.invalid();
        }
        List<MappedTranscript.Slice> anchors = new ArrayList<>();
        int references = 0;
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            for (int start = 0; start < atom.text.length();) {
                if (anchors.size() >= limits.pieces || atom.origins.size() > limits.references - references) {
                    throw RawTimeline.invalid();
                }
                int end = start;
                int count = 0;
                boolean whitespace = space(atom.text.codePointAt(start));
                while (end < atom.text.length() && count < maxPieceCodePoints &&
                        space(atom.text.codePointAt(end)) == whitespace) {
                    end += Character.charCount(atom.text.codePointAt(end));
                    count++;
                }
                anchors.add(new MappedTranscript.Slice(atom.index, start, end));
                references += atom.origins.size();
                start = end;
            }
        }
        return new SegmentationProposal(transcript, anchors, limits);
    }

    /** Bounded pieces from complete existing groups; all ranges still belong to the full document. */
    public static SegmentationProposal prepareBlock(MappedTranscript transcript, SemanticGroupPlanner.Plan basePlan,
            int firstGroup, int endGroup, int maxPieceCodePoints, Limits limits) {
        if (transcript == null || basePlan == null || !basePlan.isFor(transcript) || limits == null ||
                firstGroup < 0 || endGroup <= firstGroup || endGroup > basePlan.getGroups().size() ||
                maxPieceCodePoints <= 0 || maxPieceCodePoints > 1024 * 1024) throw RawTimeline.invalid();
        List<MappedTranscript.Slice> anchors = new ArrayList<>();
        int references = 0;
        for (int group = firstGroup; group < endGroup; group++) {
            for (MappedTranscript.Slice slice : basePlan.getGroups().get(group).slices) {
                MappedTranscript.Atom atom = transcript.getAtoms().get(slice.atomIndex);
                for (int start = slice.start; start < slice.end;) {
                    if (anchors.size() >= limits.pieces || atom.origins.size() > limits.references - references) {
                        throw RawTimeline.invalid();
                    }
                    int end = start;
                    int count = 0;
                    boolean whitespace = space(atom.text.codePointAt(start));
                    while (end < slice.end && count < maxPieceCodePoints &&
                            space(atom.text.codePointAt(end)) == whitespace) {
                        end += Character.charCount(atom.text.codePointAt(end));
                        count++;
                    }
                    anchors.add(new MappedTranscript.Slice(atom.index, start, end));
                    references += atom.origins.size();
                    start = end;
                }
            }
        }
        return new SegmentationProposal(transcript, anchors, limits, basePlan, firstGroup, endGroup);
    }

    public static SegmentationProposal fromAnchors(MappedTranscript transcript,
                                                    List<MappedTranscript.Slice> anchors, Limits limits) {
        return new SegmentationProposal(transcript, anchors, limits);
    }

    private final MappedTranscript mTranscript;
    private final SemanticGroupPlanner.Plan mBasePlan;
    private final int mFirstGroup;
    private final int mEndGroup;
    private final List<Piece> mPieces;
    private final List<AcceptedUnit> mFallback;
    private final int mMaxOutputCodePoints;
    private final int mMaxUnits;

    /** Historical offline constructor. Public factories require explicit production limits. */
    SegmentationProposal(MappedTranscript transcript, List<MappedTranscript.Slice> anchors,
                         int maxOutputCodePoints, int maxUnits) {
        this(transcript, anchors, offlineLimits(maxOutputCodePoints, maxUnits));
    }

    private static Limits offlineLimits(int output, int units) {
        if (output > RawTimeline.MAX_DOCUMENT_BYTES || units > RawTimeline.MAX_TEXT_RUNS) throw RawTimeline.invalid();
        return new Limits(RawTimeline.MAX_TEXT_RUNS, 65536, output, units);
    }

    private SegmentationProposal(MappedTranscript transcript, List<MappedTranscript.Slice> anchors, Limits limits) {
        this(transcript, anchors, limits, null, 0, 0);
    }

    /** Only prepareBlock may supply a trusted complete-group range instead of full-document anchors. */
    private SegmentationProposal(MappedTranscript transcript, List<MappedTranscript.Slice> anchors, Limits limits,
            SemanticGroupPlanner.Plan basePlan, int firstGroup, int endGroup) {
        if (transcript == null || anchors == null || limits == null || anchors.size() > limits.pieces) {
            throw RawTimeline.invalid();
        }
        List<Piece> pieces = new ArrayList<>();
        int atomIndex = 0;
        int offset = 0;
        int endAtom = transcript.getAtoms().size();
        int endOffset = 0;
        if (basePlan != null) {
            List<MappedTranscript.Slice> first = basePlan.getGroups().get(firstGroup).slices;
            List<MappedTranscript.Slice> last = basePlan.getGroups().get(endGroup - 1).slices;
            if (first.isEmpty() || last.isEmpty() || anchors.isEmpty()) throw RawTimeline.invalid();
            atomIndex = first.get(0).atomIndex;
            offset = first.get(0).start;
            MappedTranscript.Slice end = last.get(last.size() - 1);
            endAtom = end.atomIndex;
            endOffset = end.end;
            if (endOffset == transcript.getAtoms().get(endAtom).text.length()) {
                endAtom++;
                endOffset = 0;
            }
        }
        int references = 0;
        for (MappedTranscript.Slice slice : anchors) {
            if (slice == null || slice.atomIndex != atomIndex || slice.start != offset) {
                throw RawTimeline.invalid();
            }
            String text = transcript.text(slice); // Validates ranges and surrogate boundaries.
            int origins = transcript.getAtoms().get(atomIndex).origins.size();
            if (origins > limits.references - references) throw RawTimeline.invalid();
            references += origins;
            pieces.add(new Piece(pieces.size(), slice, text, transcript.getAtoms().get(atomIndex).flowIndex));
            offset = slice.end;
            if (offset == transcript.getAtoms().get(atomIndex).text.length()) {
                atomIndex++;
                offset = 0;
            }
        }
        if (atomIndex != endAtom || offset != endOffset) {
            throw RawTimeline.invalid();
        }
        mTranscript = transcript;
        mBasePlan = basePlan;
        mFirstGroup = firstGroup;
        mEndGroup = endGroup;
        mPieces = Collections.unmodifiableList(pieces);
        mMaxOutputCodePoints = limits.outputCodePoints;
        mMaxUnits = limits.units;
        List<AcceptedUnit> fallback = new ArrayList<>();
        for (int start = 0; start < pieces.size();) {
            int end = start + 1;
            while (end < pieces.size() && pieces.get(end).flowIndex == pieces.get(start).flowIndex) {
                end++;
            }
            List<Piece> sameFlow = pieces.subList(start, end);
            String text = join(sameFlow);
            fallback.add(new AcceptedUnit(sameFlow, text, text));
            start = end;
        }
        mFallback = Collections.unmodifiableList(fallback);
    }

    public boolean isFor(MappedTranscript transcript) {
        return mTranscript == transcript;
    }

    SemanticGroupPlanner.Plan basePlan() { return mBasePlan; }
    int firstGroup() { return mFirstGroup; }
    int endGroup() { return mEndGroup; }

    /** Commit-time identity check across packages; full proposals have no partition dependency. */
    public boolean canApplyTo(SemanticGroupPlanner.Plan plan) {
        return plan != null && plan.isFor(mTranscript) && (mBasePlan == null || mBasePlan == plan);
    }

    public List<Piece> getPieces() {
        return mPieces;
    }

    /** A completed decision cannot be transplanted between equal-looking proposals. */
    public boolean owns(Decision decision) {
        return decision != null && decision.mOwner == this;
    }

    /** Partial anchors retain every original clock window and alias; no proportional timing. */
    public List<MappedTranscript.Reference> getReferences(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= mPieces.size()) throw RawTimeline.invalid();
        return mTranscript.references(mPieces.get(pieceIndex).slice);
    }

    /** All-or-nothing acceptance. Partial, invalid, or truncated output must not lose source text. */
    public Decision evaluate(List<Unit> units, TextPolicy policy, Completion completion) {
        // Completion comes from a future provider adapter, never from the proposed content itself.
        if (completion != Completion.COMPLETE) {
            return reject(Problem.INCOMPLETE_COMPLETION);
        }
        if (units == null || policy == null) {
            return reject(Problem.MISSING_OUTPUT);
        }
        if (units.size() > mMaxUnits) {
            return reject(Problem.OVER_BUDGET);
        }
        int cursor = 0;
        int characters = 0;
        List<AcceptedUnit> accepted = new ArrayList<>();
        for (Unit unit : units) {
            if (unit == null || unit.sourceRefs == null || unit.sourceRefs.isEmpty() || unit.suggestedSource == null) {
                return reject(Problem.MISSING_OUTPUT);
            }
            if (unit.sourceRefs.size() > mPieces.size() ||
                    unit.suggestedSource.length() > 2L * mMaxOutputCodePoints) {
                return reject(Problem.OVER_BUDGET);
            }
            characters += unit.suggestedSource.codePointCount(0, unit.suggestedSource.length());
            if (characters > mMaxOutputCodePoints) {
                return reject(Problem.OVER_BUDGET);
            }
            List<Piece> selected = new ArrayList<>();
            int flow = -1;
            for (String id : unit.sourceRefs) {
                if (cursor >= mPieces.size() || !mPieces.get(cursor).id.equals(id)) {
                    return reject(Problem.INVALID_REFERENCE);
                }
                Piece piece = mPieces.get(cursor++);
                if (flow >= 0 && flow != piece.flowIndex) {
                    return reject(Problem.CROSS_FLOW);
                }
                flow = piece.flowIndex;
                selected.add(piece);
            }
            String original = join(selected);
            if (!(policy == TextPolicy.EXACT ? original.equals(unit.suggestedSource) :
                    punctuationOnly(original, unit.suggestedSource,
                            insertionBoundary(cursor - selected.size()), insertionBoundary(cursor)))) {
                return reject(Problem.TEXT_CHANGED);
            }
            accepted.add(new AcceptedUnit(selected, original, unit.suggestedSource));
        }
        return cursor == mPieces.size() ? new Decision(this, Problem.NONE, accepted) : reject(Problem.INCOMPLETE);
    }

    private Decision reject(Problem problem) {
        return new Decision(this, problem, mFallback);
    }

    private static String join(List<Piece> pieces) {
        StringBuilder text = new StringBuilder();
        for (Piece piece : pieces) {
            text.append(piece.text);
        }
        return text.toString();
    }

    private boolean insertionBoundary(int pieceIndex) {
        if (pieceIndex == 0 || pieceIndex == mPieces.size()) {
            if (mBasePlan == null) return true;
            Piece edge = mPieces.get(pieceIndex == 0 ? 0 : pieceIndex - 1);
            return sourceBoundary(edge.slice.atomIndex, pieceIndex == 0 ? edge.slice.start : edge.slice.end);
        }
        Piece left = mPieces.get(pieceIndex - 1);
        Piece right = mPieces.get(pieceIndex);
        return left.flowIndex != right.flowIndex || space(left.text.charAt(left.text.length() - 1)) ||
                space(right.text.charAt(0));
    }

    /** A block edge inside a word is not a document or flow edge. */
    private boolean sourceBoundary(int atomIndex, int offset) {
        List<MappedTranscript.Atom> atoms = mTranscript.getAtoms();
        MappedTranscript.Atom atom = atoms.get(atomIndex);
        MappedTranscript.Atom left = atom;
        MappedTranscript.Atom right = atom;
        int leftEnd = offset;
        int rightStart = offset;
        if (offset == 0) {
            if (atomIndex == 0 || atoms.get(atomIndex - 1).flowIndex != atom.flowIndex) return true;
            left = atoms.get(atomIndex - 1);
            leftEnd = left.text.length();
        }
        if (offset == atom.text.length()) {
            if (atomIndex + 1 == atoms.size() || atoms.get(atomIndex + 1).flowIndex != atom.flowIndex) return true;
            right = atoms.get(atomIndex + 1);
            rightStart = 0;
        }
        return space(left.text.codePointBefore(leftEnd)) || space(right.text.codePointAt(rightStart));
    }

    private static boolean space(int value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    /** Preserve source characters; unit boundaries must also be safe in the surrounding source flow. */
    private static boolean punctuationOnly(String source, String suggestion, boolean before, boolean after) {
        int cursor = 0;
        for (int i = 0; i < suggestion.length(); i++) {
            char value = suggestion.charAt(i);
            if (cursor < source.length() && asciiEqual(source.charAt(cursor), value)) {
                cursor++;
            } else if (!(addedPunctuation(value) && (cursor == 0 ? before :
                    cursor == source.length() ? after : space(source.charAt(cursor)) || space(source.charAt(cursor - 1))))) {
                return false;
            }
        }
        return cursor == source.length();
    }

    private static boolean asciiEqual(char first, char second) {
        if (first == second) {
            return true;
        }
        return first >= 'A' && first <= 'Z' && first + 32 == second ||
                first >= 'a' && first <= 'z' && first - 32 == second;
    }

    private static boolean addedPunctuation(char value) {
        // Apostrophes/hyphens, decimal separators inside words, and existing punctuation are preserved.
        return value == ',' || value == '.' || value == '!' || value == '?' || value == ':' || value == ';' ||
                value == '…' || value == '"' || value == '“' || value == '”';
    }
}
