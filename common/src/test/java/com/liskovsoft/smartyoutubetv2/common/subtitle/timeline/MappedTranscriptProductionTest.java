package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.*;

public class MappedTranscriptProductionTest {
    @Test
    public void independentCuesAndDuplicateTextKeepDistinctFlowsAndFullProvenance() {
        RawTimeline raw = parse("WEBVTT\n\nid\n00:03.000 --> 00:30.000\nrepeat\n\n" +
                "id\n00:01.000 --> 00:10.000\nrepeat\n", 100);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 2);
        assertTrue(mapped.isFor(raw));
        assertEquals(2, mapped.getAtoms().size());
        for (int i = 0; i < 2; i++) {
            MappedTranscript.Atom atom = mapped.getAtoms().get(i);
            assertEquals(i, atom.index);
            assertEquals(i, atom.flowIndex);
            assertEquals("repeat", atom.text);
            assertEquals(1, atom.origins.size());
            MappedTranscript.Origin origin = atom.origins.get(0);
            assertEquals(i, origin.cueIndex);
            assertEquals(i, origin.sourceIndex);
            assertEquals(0, origin.runIndex);
            assertEquals(raw.getCues().get(i).startUs, origin.timingStartUs);
            assertEquals(raw.getCues().get(i).endUs, origin.timingEndUs);
            assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, origin.timingKind);
        }
    }

    @Test
    public void partialTextRetainsWholeCoarseClockWithoutInventingWordTiming() {
        RawTimeline raw = parse("WEBVTT\n\n00:01.000 --> 00:20.000\nA word 😀 end\n", 100);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 8);
        MappedTranscript.Slice slice = new MappedTranscript.Slice(0, 2, 6);
        assertEquals("word", mapped.text(slice));
        MappedTranscript.Reference ref = mapped.references(slice).get(0);
        assertEquals(2, ref.textStart);
        assertEquals(6, ref.textEnd);
        assertFalse(ref.wholeRun);
        assertEquals(1_000_000, ref.origin.activeStartUs);
        assertEquals(20_000_000, ref.origin.activeEndUs);
        assertEquals(1_000_000, ref.origin.timingStartUs);
        assertEquals(20_000_000, ref.origin.timingEndUs);
        invalid(() -> mapped.text(new MappedTranscript.Slice(0, 7, 8)));
        invalid(() -> mapped.references(new MappedTranscript.Slice(0, 8, 9)));
        assertEquals("😀", mapped.text(new MappedTranscript.Slice(0, 7, 9)));
    }

    @Test
    public void timestampWindowsAndLineBreaksKeepRunOffsetsAndVisibleIntervals() {
        RawTimeline raw = parse("WEBVTT\n\n00:01.000 --> 00:05.000\nA<00:02.000>B\nC\n", 100);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 8);
        assertEquals(2, mapped.getAtoms().size());
        StringBuilder all = new StringBuilder();
        for (MappedTranscript.Atom atom : mapped.getAtoms()) {
            all.append(atom.text);
            assertEquals(0, atom.flowIndex);
            MappedTranscript.Reference ref = mapped.references(
                    new MappedTranscript.Slice(atom.index, 0, atom.text.length())).get(0);
            RawTimeline.TextRun source = raw.getCues().get(0).runs.get(atom.index);
            assertEquals(source.textStart, ref.textStart);
            assertEquals(source.textEnd, ref.textEnd);
            assertEquals(source.activeStartUs, ref.origin.activeStartUs);
            assertEquals(source.activeEndUs, ref.origin.activeEndUs);
            assertEquals(source.timingStartUs, ref.origin.timingStartUs);
            assertEquals(source.timingEndUs, ref.origin.timingEndUs);
            assertEquals(source.timingKind, ref.origin.timingKind);
            assertEquals(source.lineBreak, ref.origin.lineBreak);
            assertTrue(ref.wholeRun);
        }
        assertEquals("AB\nC", all.toString());
        assertEquals("B\nC", mapped.getAtoms().get(1).text);
        assertFalse(mapped.getAtoms().get(1).origins.get(0).lineBreak);
        MappedTranscript.Slice newline = new MappedTranscript.Slice(1, 1, 2);
        assertEquals("\n", mapped.text(newline));
        MappedTranscript.Reference newlineRef = mapped.references(newline).get(0);
        assertEquals(2, newlineRef.textStart);
        assertEquals(3, newlineRef.textEnd);
        assertFalse(newlineRef.wholeRun);
        assertEquals(2_000_000, newlineRef.origin.timingStartUs);
        assertEquals(5_000_000, newlineRef.origin.timingEndUs);
        assertEquals(1_000_000, mapped.getAtoms().get(1).origins.get(0).activeStartUs);
        assertEquals(2_000_000, mapped.getAtoms().get(1).origins.get(0).timingStartUs);
    }

    @Test
    public void explicitBudgetSupportsMoreThanHistoricalOfflineAtomsAndRejectsWholeOverflow() {
        StringBuilder document = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < 2050; i++) document.append("00:01.000 --> 00:02.000\nx\n\n");
        RawTimeline raw = parse(document.toString(), 4096);
        assertEquals(2050, MappedTranscript.fromRaw(raw, 2050).getAtoms().size());
        invalid(() -> MappedTranscript.fromRaw(raw, 2049));
        invalid(() -> MappedTranscript.fromRaw(raw, 0));
        invalid(() -> MappedTranscript.fromRaw(raw, 65537));
        invalid(() -> MappedTranscript.fromRaw(null, 1));
    }

    @Test
    public void emptyDocumentIsValidWithoutInventingAtoms() {
        RawTimeline raw = parse("WEBVTT\n\n", 100);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 1);
        assertTrue(mapped.getAtoms().isEmpty());
        invalid(() -> mapped.text(new MappedTranscript.Slice(0, 0, 1)));
    }

    @Test
    public void mappedListsAreImmutableAndEqualLookingSourceIsNotTheSameDocument() {
        RawTimeline raw = parse("WEBVTT\n\n00:01.000 --> 00:02.000\nx\n", 100);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 1);
        RawTimeline other = parse("WEBVTT\n\n00:01.000 --> 00:02.000\nx\n", 100);
        assertFalse(mapped.isFor(other));
        try { mapped.getAtoms().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { mapped.getAtoms().get(0).origins.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        List<MappedTranscript.Reference> refs = mapped.references(new MappedTranscript.Slice(0, 0, 1));
        try { refs.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    private static RawTimeline parse(String text, int runs) {
        return RawWebvttParser.parse(text.getBytes(Charset.forName("UTF-8")), 0,
                new RawTimeline.Limits(1024 * 1024, 4096, runs));
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected complete mapping rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
        }
    }
}
