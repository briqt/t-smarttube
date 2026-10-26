package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Source-preserving text groups. Candidate boundaries do not create subtitle layout or timing. */
public final class SemanticGroupPlanner {
    public enum Boundary {
        PUNCTUATION,
        FLOW_END,
        /** Boundary accepted from an owned typed proposal; source text and clocks remain unchanged. */
        TYPED,
        /** A caller-requested length break requiring review, not a semantic or timing assertion. */
        BUDGET
    }

    public static final class Group {
        public final List<MappedTranscript.Slice> slices;
        public final Boundary boundary;

        private Group(List<MappedTranscript.Slice> slices, Boundary boundary) {
            this.slices = Collections.unmodifiableList(new ArrayList<>(slices));
            this.boundary = boundary;
        }

        String text(MappedTranscript transcript) {
            StringBuilder text = new StringBuilder();
            for (MappedTranscript.Slice slice : slices) {
                text.append(transcript.text(slice));
            }
            return text.toString();
        }
    }

    public static final class Limits {
        public final int groups;
        public final int slices;
        public final int references;

        /** Allocation budgets, not translation batch sizes or display defaults. */
        public Limits(int groups, int slices, int references) {
            if (groups <= 0 || groups > 65536 || slices <= 0 || slices > 65536 ||
                    references <= 0 || references > 65536) throw RawTimeline.invalid();
            this.groups = groups;
            this.slices = slices;
            this.references = references;
        }
    }

    /** Belongs to one exact mapped document; contains no new start/end times. */
    public static final class Plan {
        private final MappedTranscript mTranscript;
        private final List<Group> mGroups;

        private Plan(MappedTranscript transcript, List<Group> groups) {
            mTranscript = transcript;
            mGroups = Collections.unmodifiableList(new ArrayList<>(groups));
        }

        public boolean isFor(MappedTranscript transcript) {
            return mTranscript == transcript;
        }

        public List<Group> getGroups() {
            return mGroups;
        }

        public String getText(int index) {
            return group(index).text(mTranscript);
        }

        public int getFlowIndex(int index) {
            return mTranscript.getAtoms().get(group(index).slices.get(0).atomIndex).flowIndex;
        }

        public List<MappedTranscript.Reference> getReferences(int index) {
            List<MappedTranscript.Reference> references = new ArrayList<>();
            for (MappedTranscript.Slice slice : group(index).slices) {
                references.addAll(mTranscript.references(slice));
            }
            return Collections.unmodifiableList(references);
        }

        /** Reuse identical source partitions and their boundary semantics; a no-op returns the old Plan. */
        public Plan retainUnchanged(Plan previous) {
            if (previous == null || previous.mTranscript != mTranscript) throw RawTimeline.invalid();
            List<Group> result = new ArrayList<>();
            int oldIndex = 0;
            boolean identical = mGroups.size() == previous.mGroups.size();
            for (int index = 0; index < mGroups.size(); index++) {
                Group next = mGroups.get(index);
                MappedTranscript.Slice first = next.slices.get(0);
                while (oldIndex < previous.mGroups.size() && before(previous.mGroups.get(oldIndex).slices.get(0), first)) oldIndex++;
                Group old = oldIndex < previous.mGroups.size() ? previous.mGroups.get(oldIndex) : null;
                Group chosen = sameSlices(old, next) ? old : next;
                result.add(chosen);
                identical &= index < previous.mGroups.size() && chosen == previous.mGroups.get(index);
            }
            return identical ? previous : new Plan(mTranscript, result);
        }

        private static boolean before(MappedTranscript.Slice first, MappedTranscript.Slice second) {
            return first.atomIndex < second.atomIndex || first.atomIndex == second.atomIndex && first.start < second.start;
        }

        private static boolean sameSlices(Group first, Group second) {
            if (first == null || first.slices.size() != second.slices.size()) return false;
            for (int index = 0; index < first.slices.size(); index++) {
                MappedTranscript.Slice a = first.slices.get(index), b = second.slices.get(index);
                if (a.atomIndex != b.atomIndex || a.start != b.start || a.end != b.end) return false;
            }
            return true;
        }

        /** Active source intervals only; a text cut does not create a finer playback clock. */
        public List<Integer> getGroupsAt(long timeUs) {
            List<Integer> active = new ArrayList<>();
            for (int i = 0; i < mGroups.size(); i++) {
                if (activeAt(mGroups.get(i), timeUs)) active.add(i);
            }
            return Collections.unmodifiableList(active);
        }

        private boolean activeAt(Group group, long timeUs) {
            for (MappedTranscript.Slice slice : group.slices) {
                for (MappedTranscript.Origin origin : mTranscript.getAtoms().get(slice.atomIndex).origins) {
                    if (origin.activeStartUs <= timeUs && timeUs < origin.activeEndUs) return true;
                }
            }
            return false;
        }

        private Group group(int index) {
            if (index < 0 || index >= mGroups.size()) throw RawTimeline.invalid();
            return mGroups.get(index);
        }
    }

    // Deliberately small and conservative: an abbreviation can also occur at a real sentence end.
    private static final String[] ABBREVIATIONS = {
            "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "Jr.", "Sr.", "e.g.", "i.e.", "U.S.", "U.K."
    };

    private SemanticGroupPlanner() {
    }

    /** Historical offline entry; production callers must choose preserve or candidates and limits. */
    static List<Group> plan(MappedTranscript transcript, int maxCodePoints) {
        if (maxCodePoints > RawTimeline.MAX_DOCUMENT_BYTES) throw RawTimeline.invalid();
        return candidates(transcript, maxCodePoints, new Limits(65536, 65536, 65536)).getGroups();
    }

    /** Preserve independent flows without punctuation or length cuts. Request/display policy is separate. */
    public static Plan preserve(MappedTranscript transcript, Limits limits) {
        if (transcript == null || limits == null) throw RawTimeline.invalid();
        Collector groups = new Collector(transcript, limits);
        List<MappedTranscript.Atom> atoms = transcript.getAtoms();
        for (int first = 0; first < atoms.size();) {
            int flow = atoms.get(first).flowIndex;
            List<MappedTranscript.Slice> slices = new ArrayList<>();
            int next = first;
            while (next < atoms.size() && atoms.get(next).flowIndex == flow) {
                MappedTranscript.Atom atom = atoms.get(next);
                if (slices.size() >= groups.remainingSlices()) throw RawTimeline.invalid();
                slices.add(new MappedTranscript.Slice(next++, 0, atom.text.length()));
            }
            groups.add(slices, Boundary.FLOW_END);
            first = next;
        }
        return groups.finish();
    }

    /** Source-only grouping from a completed owned decision; never substitutes suggested wording. */
    public static Plan fromDecision(MappedTranscript transcript, SegmentationProposal proposal,
                                    SegmentationProposal.Decision decision, Limits limits) {
        if (transcript == null || proposal == null || decision == null || limits == null ||
                !proposal.isFor(transcript) || !proposal.owns(decision) || !decision.accepted()) throw RawTimeline.invalid();
        Collector groups = new Collector(transcript, limits);
        Plan base = proposal.basePlan();
        if (base != null) {
            for (int index = 0; index < proposal.firstGroup(); index++) groups.add(base.getGroups().get(index));
        }
        List<SegmentationProposal.Piece> expected = proposal.getPieces();
        int cursor = 0;
        for (SegmentationProposal.AcceptedUnit unit : decision.units) {
            if (unit == null || unit.pieces.isEmpty()) throw RawTimeline.invalid();
            List<MappedTranscript.Slice> slices = new ArrayList<>();
            StringBuilder original = new StringBuilder();
            int flow = unit.pieces.get(0).flowIndex;
            for (SegmentationProposal.Piece piece : unit.pieces) {
                if (cursor >= expected.size() || piece != expected.get(cursor++) || piece.flowIndex != flow) {
                    throw RawTimeline.invalid();
                }
                original.append(transcript.text(piece.slice));
                MappedTranscript.Slice previous = slices.isEmpty() ? null : slices.get(slices.size() - 1);
                if (previous != null && previous.atomIndex == piece.slice.atomIndex && previous.end == piece.slice.start) {
                    slices.set(slices.size() - 1, new MappedTranscript.Slice(previous.atomIndex, previous.start, piece.slice.end));
                } else {
                    slices.add(piece.slice);
                }
            }
            if (!original.toString().equals(unit.originalText)) throw RawTimeline.invalid();
            groups.add(slices, Boundary.TYPED);
        }
        if (cursor != expected.size()) throw RawTimeline.invalid();
        if (base != null) {
            for (int index = proposal.endGroup(); index < base.getGroups().size(); index++) groups.add(base.getGroups().get(index));
        }
        Plan result = groups.finish();
        return base == null ? result : result.retainUnchanged(base);
    }

    /** Explicit rule candidates, not automatic ASR recognition, word timing, or a display layout. */
    public static Plan candidates(MappedTranscript transcript, int maxCodePoints, Limits limits) {
        if (transcript == null || limits == null || maxCodePoints <= 0 || maxCodePoints > 1024 * 1024) {
            throw RawTimeline.invalid();
        }
        List<MappedTranscript.Atom> atoms = transcript.getAtoms();
        Collector groups = new Collector(transcript, limits);
        SliceCursor slices = new SliceCursor(atoms);
        for (int first = 0; first < atoms.size();) {
            int flow = atoms.get(first).flowIndex;
            StringBuilder text = new StringBuilder();
            int next = first;
            while (next < atoms.size() && atoms.get(next).flowIndex == flow) {
                text.append(atoms.get(next++).text);
            }
            planFlow(text.toString(), maxCodePoints, slices, groups);
            first = next;
        }
        return groups.finish();
    }

    private static void planFlow(String text, int budget, SliceCursor slices, Collector groups) {
        int start = 0;
        int punctuation = nextPunctuation(text, 0);
        while (start < text.length()) {
            int end = start;
            int whitespace = -1;
            int count = 0;
            while (end < text.length() && end != punctuation && count < budget) {
                int codePoint = text.codePointAt(end);
                end += Character.charCount(codePoint);
                count++;
                if (isWhitespace(codePoint)) {
                    whitespace = end;
                }
            }
            Boundary boundary;
            if (end == punctuation) {
                boundary = Boundary.PUNCTUATION;
                punctuation = nextPunctuation(text, end);
            } else if (end == text.length()) {
                boundary = Boundary.FLOW_END;
            } else {
                boundary = Boundary.BUDGET;
                if (whitespace > start) {
                    end = whitespace; // Keep the whitespace; no trimming or invented separator.
                }
            }
            groups.add(slices.take(end - start, groups.remainingSlices()), boundary);
            start = end;
        }
    }

    /** Scan independently of budget cuts, so even a very long punctuation run is scanned once. */
    private static int nextPunctuation(String text, int start) {
        for (int cursor = start; cursor < text.length();) {
            if (!isTerminal(text.charAt(cursor))) {
                cursor += Character.charCount(text.codePointAt(cursor));
                continue;
            }
            int first = cursor;
            boolean cjk = false;
            while (cursor < text.length() && isTerminal(text.charAt(cursor))) {
                cjk |= isCjkTerminal(text.charAt(cursor++));
            }
            int punctuationEnd = cursor;
            // Decimal and URL/email/domain internal dots have no such following boundary context.
            boolean asciiContext = cursor == text.length() || isWhitespace(text.codePointAt(cursor)) ||
                    isClosing(text.charAt(cursor));
            if (!cjk && (!asciiContext || (punctuationEnd == first + 1 &&
                    text.charAt(first) == '.' && isAbbreviationOrInitial(text, first)))) {
                continue;
            }
            // A quote can be followed by an outer sentence mark: Did she say "Go!"?
            while (cursor < text.length() &&
                    (isClosing(text.charAt(cursor)) || isTerminal(text.charAt(cursor)))) {
                cursor++;
            }
            return cursor;
        }
        return -1;
    }

    private static boolean isAbbreviationOrInitial(String text, int dot) {
        for (String abbreviation : ABBREVIATIONS) {
            int start = dot + 1 - abbreviation.length();
            if (start >= 0 && text.regionMatches(true, start, abbreviation, 0, abbreviation.length()) &&
                    (start == 0 || !isWord(text.codePointBefore(start)))) {
                return true;
            }
        }
        // Keep A. Smith and A. B. Smith together, even across atom boundaries.
        return dot > 0 && text.charAt(dot - 1) >= 'A' && text.charAt(dot - 1) <= 'Z' &&
                (dot == 1 || !isWord(text.codePointBefore(dot - 1)));
    }

    private static boolean isWord(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isTerminal(char value) {
        return value == '.' || value == '?' || value == '!' || isCjkTerminal(value);
    }

    private static boolean isCjkTerminal(char value) {
        return value == '。' || value == '！' || value == '？';
    }

    private static boolean isClosing(char value) {
        return value == '"' || value == '\'' || value == ')' || value == ']' || value == '}' ||
                value == '”' || value == '’' || value == '」' || value == '』' ||
                value == '»' || value == '›' || value == '》' || value == '】';
    }

    /** Reject the whole plan before publishing any prefix, including aliased-reference amplification. */
    private static final class Collector {
        private final MappedTranscript mTranscript;
        private final Limits mLimits;
        private final List<Group> mGroups = new ArrayList<>();
        private int mSlices;
        private int mReferences;

        Collector(MappedTranscript transcript, Limits limits) {
            mTranscript = transcript;
            mLimits = limits;
        }

        void add(List<MappedTranscript.Slice> slices, Boundary boundary) {
            count(slices);
            mGroups.add(new Group(slices, boundary));
        }

        void add(Group group) {
            count(group.slices);
            mGroups.add(group);
        }

        private void count(List<MappedTranscript.Slice> slices) {
            if (mGroups.size() >= mLimits.groups || slices.size() > mLimits.slices - mSlices) {
                throw RawTimeline.invalid();
            }
            for (MappedTranscript.Slice slice : slices) {
                int references = mTranscript.getAtoms().get(slice.atomIndex).origins.size();
                if (references > mLimits.references - mReferences) throw RawTimeline.invalid();
                mReferences += references;
            }
            mSlices += slices.size();
        }

        int remainingSlices() {
            return mLimits.slices - mSlices;
        }

        Plan finish() {
            return new Plan(mTranscript, mGroups);
        }
    }

    /** Consume original UTF-16 ranges in order; references remain owned by MappedTranscript. */
    private static final class SliceCursor {
        private final List<MappedTranscript.Atom> mAtoms;
        private int mAtom;
        private int mOffset;

        SliceCursor(List<MappedTranscript.Atom> atoms) {
            mAtoms = atoms;
        }

        List<MappedTranscript.Slice> take(int length, int maxSlices) {
            List<MappedTranscript.Slice> slices = new ArrayList<>();
            while (length > 0) {
                if (slices.size() >= maxSlices) throw RawTimeline.invalid();
                MappedTranscript.Atom atom = mAtoms.get(mAtom);
                int end = mOffset + Math.min(length, atom.text.length() - mOffset);
                slices.add(new MappedTranscript.Slice(mAtom, mOffset, end));
                length -= end - mOffset;
                mOffset = end;
                if (mOffset == atom.text.length()) {
                    mAtom++;
                    mOffset = 0;
                }
            }
            return slices;
        }
    }
}
