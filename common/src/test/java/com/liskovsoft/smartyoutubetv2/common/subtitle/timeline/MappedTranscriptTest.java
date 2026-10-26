package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class MappedTranscriptTest {
    @Test
    public void everyRawRunMustAppearOnceIncludingAliases() {
        RawTimeline raw = raw();
        MappedTranscript.Atom first = atom(raw, 0, 0, 0);
        invalid(() -> new MappedTranscript(raw, Collections.singletonList(first)));
        MappedTranscript.Atom duplicate = atom(raw, 1, 0, 0);
        invalid(() -> new MappedTranscript(raw, Arrays.asList(first, duplicate)));
        MappedTranscript transcript = new MappedTranscript(raw, Arrays.asList(first, atom(raw, 1, 1, 1)));
        assertEquals(2, transcript.getAtoms().size());
    }

    @Test
    public void anAliasMustReferenceEqualRawTextAndTheActualSourceOrdinal() {
        RawTimeline raw = raw();
        List<MappedTranscript.Origin> origins = Arrays.asList(new MappedTranscript.Origin(0, raw.getCues().get(0), 0),
                new MappedTranscript.Origin(1, raw.getCues().get(1), 0));
        invalid(() -> new MappedTranscript(raw, Collections.singletonList(new MappedTranscript.Atom(0, 0, "A", origins))));
        RawTimeline.Cue wrongOrdinal = new RawTimeline.Cue(99, null, 1, 2, "A");
        MappedTranscript.Origin wrong = new MappedTranscript.Origin(0, wrongOrdinal, 0);
        invalid(() -> new MappedTranscript(raw, Arrays.asList(new MappedTranscript.Atom(0, 0, "A", Collections.singletonList(wrong)),
                atom(raw, 1, 1, 1))));
    }

    @Test
    public void rangeClockAndBreakEvidenceAreValidatedAgainstTheSourceRun() {
        RawTimeline raw = raw();
        RawTimeline.Cue wrongTime = new RawTimeline.Cue(0, null, 2, 4, "A");
        MappedTranscript.Origin wrong = new MappedTranscript.Origin(0, wrongTime, 0);
        invalid(() -> new MappedTranscript(raw, Arrays.asList(new MappedTranscript.Atom(0, 0, "A", Collections.singletonList(wrong)),
                atom(raw, 1, 1, 1))));
    }

    @Test
    public void atomOrderAndFlowOrderCannotRewind() {
        RawTimeline raw = raw();
        invalid(() -> new MappedTranscript(raw, Arrays.asList(atom(raw, 1, 0, 0), atom(raw, 0, 1, 1))));
        invalid(() -> new MappedTranscript(raw, Arrays.asList(atom(raw, 0, 2, 0), atom(raw, 1, 1, 1))));
        invalid(() -> new MappedTranscript(raw, Arrays.asList(atom(raw, 0, -1, 0), atom(raw, 1, 0, 1))));
    }

    @Test
    public void callerListsCannotChangeTheFrozenMapping() {
        RawTimeline raw = raw();
        List<MappedTranscript.Origin> origins = new ArrayList<>();
        origins.add(new MappedTranscript.Origin(0, raw.getCues().get(0), 0));
        MappedTranscript.Atom first = new MappedTranscript.Atom(0, 0, "A", origins);
        origins.clear();
        List<MappedTranscript.Atom> atoms = new ArrayList<>(Arrays.asList(first, atom(raw, 1, 1, 1)));
        MappedTranscript transcript = new MappedTranscript(raw, atoms);
        atoms.clear();
        assertEquals(2, transcript.getAtoms().size());
        assertEquals(1, transcript.getAtoms().get(0).origins.size());
    }

    @Test
    public void invalidLocalReferencesFailBeforeReadingAnotherAtom() {
        MappedTranscript transcript = RollingTextNormalizer.normalize(raw(), null);
        invalid(() -> new MappedTranscript.Slice(-1, 0, 1));
        invalid(() -> new MappedTranscript.Slice(0, -1, 1));
        invalid(() -> new MappedTranscript.Slice(0, 1, 1));
        invalid(() -> transcript.references(new MappedTranscript.Slice(0, 0, 2)));
        invalid(() -> transcript.text(null));
    }

    private static RawTimeline raw() {
        return new RawTimeline(new byte[]{1}, Arrays.asList(new RawTimeline.Cue(0, null, 1, 2, "A"),
                new RawTimeline.Cue(1, null, 2, 3, "B")));
    }

    private static MappedTranscript.Atom atom(RawTimeline raw, int index, int flow, int cueIndex) {
        RawTimeline.Cue cue = raw.getCues().get(cueIndex);
        return new MappedTranscript.Atom(index, flow, cue.mappingText,
                Collections.singletonList(new MappedTranscript.Origin(cueIndex, cue, 0)));
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid mapping rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
        }
    }
}
