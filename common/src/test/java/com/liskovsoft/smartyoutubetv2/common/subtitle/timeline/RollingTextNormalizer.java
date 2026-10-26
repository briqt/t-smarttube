package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test-only exact suffix/prefix de-rolling; no word guessing, case folding, or text-only deduplication. */
final class RollingTextNormalizer {
    private RollingTextNormalizer() {
    }

    /**
     * Null/-1 preserves independent cues. Nonnegative IDs assert confirmed same-source rolling ASR
     * groups; callers must not derive them from matching text, display names, ASR flags, or language.
     * No current producer/player supplies these hints. Only explicit VTT timing-window runs match.
     */
    static MappedTranscript normalize(RawTimeline raw, int[] confirmedRollingGroups) {
        if (raw == null || (confirmedRollingGroups != null && confirmedRollingGroups.length != raw.getCues().size())) {
            throw RawTimeline.invalid();
        }
        List<MutableAtom> atoms = new ArrayList<>();
        List<Integer> previous = new ArrayList<>();
        RawTimeline.Cue previousCue = null;
        Set<Integer> sourceIndices = new HashSet<>();
        int previousGroup = -1;
        int flow = -1;
        for (int cueIndex = 0; cueIndex < raw.getCues().size(); cueIndex++) {
            RawTimeline.Cue cue = raw.getCues().get(cueIndex);
            int group = confirmedRollingGroups == null ? -1 : confirmedRollingGroups[cueIndex];
            if (group < -1 || !sourceIndices.add(cue.sourceIndex)) {
                throw RawTimeline.invalid();
            }
            int overlap = 0;
            if (previousCue != null && group >= 0 && group == previousGroup &&
                    cue.startUs >= previousCue.startUs && cue.startUs < previousCue.endUs) {
                overlap = overlap(previous, atoms, cue);
            }
            if (overlap == 0) {
                flow++;
            }
            List<Integer> current = new ArrayList<>();
            for (int runIndex = 0; runIndex < cue.runs.size(); runIndex++) {
                MappedTranscript.Origin origin = new MappedTranscript.Origin(cueIndex, cue, runIndex);
                if (runIndex < overlap) {
                    int atomIndex = previous.get(previous.size() - overlap + runIndex);
                    atoms.get(atomIndex).origins.add(origin);
                    current.add(atomIndex);
                } else {
                    RawTimeline.TextRun run = cue.runs.get(runIndex);
                    MutableAtom atom = new MutableAtom(atoms.size(), flow,
                            cue.mappingText.substring(run.textStart, run.textEnd), origin);
                    atoms.add(atom);
                    current.add(atom.index);
                }
            }
            previous = current;
            previousCue = cue;
            previousGroup = group;
        }
        List<MappedTranscript.Atom> frozen = new ArrayList<>();
        for (MutableAtom atom : atoms) {
            frozen.add(new MappedTranscript.Atom(atom.index, atom.flow, atom.text, atom.origins));
        }
        return new MappedTranscript(raw, frozen);
    }

    private static int overlap(List<Integer> previous, List<MutableAtom> atoms, RawTimeline.Cue cue) {
        for (int count = Math.min(previous.size(), cue.runs.size()); count > 0; count--) {
            boolean matches = true;
            for (int i = 0; i < count; i++) {
                MutableAtom atom = atoms.get(previous.get(previous.size() - count + i));
                MappedTranscript.Origin origin = atom.origins.get(atom.origins.size() - 1);
                RawTimeline.TextRun run = cue.runs.get(i);
                if (origin.timingKind != RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW ||
                        run.timingKind != origin.timingKind || run.timingStartUs != origin.timingStartUs ||
                        run.timingEndUs != origin.timingEndUs ||
                        !atom.text.equals(cue.mappingText.substring(run.textStart, run.textEnd))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return count;
            }
        }
        return 0;
    }

    private static final class MutableAtom {
        final int index;
        final int flow;
        final String text;
        final List<MappedTranscript.Origin> origins = new ArrayList<>();

        MutableAtom(int index, int flow, String text, MappedTranscript.Origin origin) {
            this.index = index;
            this.flow = flow;
            this.text = text;
            origins.add(origin);
        }
    }
}
