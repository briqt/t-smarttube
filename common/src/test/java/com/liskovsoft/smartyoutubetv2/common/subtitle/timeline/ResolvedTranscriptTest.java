package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ResolvedTranscriptTest {
    @Test
    public void partialRunLedgerRetainsEveryOccurrenceAndItsOwnUnchangedClock() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\nalpha beta\n\n00:03.000 --> 00:05.000\nalpha beta\ngamma\n", 0);
        RawTimeline.Cue first = raw.getCues().get(0), second = raw.getCues().get(1);
        MappedTranscript mapped = MappedTranscript.resolved(raw, Arrays.asList(
                atom(0, "alpha beta", new MappedTranscript.Origin(0, first, 0, 0, 10, false),
                        new MappedTranscript.Origin(1, second, 0, 0, 10, true)),
                atom(1, "\n", new MappedTranscript.Origin(1, second, 0, 10, 11, false)),
                atom(2, "gamma", new MappedTranscript.Origin(1, second, 0, 11, 16, false))), 32);
        assertEquals(1, mapped.normalizationVersion());
        assertEquals("alpha beta\ngamma", text(mapped));
        List<MappedTranscript.Reference> whole = mapped.references(new MappedTranscript.Slice(0, 0, 10));
        assertEquals(2, whole.size());
        assertTrue(whole.get(0).wholeRun);
        assertFalse(whole.get(1).wholeRun);
        assertEquals(1_000_000, whole.get(0).origin.timingStartUs);
        assertEquals(3_000_000, whole.get(0).origin.timingEndUs);
        assertEquals(3_000_000, whole.get(1).origin.timingStartUs);
        assertEquals(5_000_000, whole.get(1).origin.timingEndUs);
        List<MappedTranscript.Reference> partial = mapped.references(new MappedTranscript.Slice(0, 0, 5));
        assertFalse(partial.get(0).wholeRun);
        assertFalse(partial.get(1).wholeRun);
        assertEquals(1_000_000, partial.get(0).origin.timingStartUs);
        assertEquals(3_000_000, partial.get(0).origin.timingEndUs);
        assertEquals(0, MappedTranscript.fromRaw(raw, 32).normalizationVersion());
    }

    @Test
    public void aDuplicateSourceRangeCannotCompensateForAnUncoveredCharacter() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\naa\n", 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        invalid(() -> MappedTranscript.resolved(raw, Arrays.asList(
                atom(0, "a", new MappedTranscript.Origin(0, cue, 0, 0, 1, false)),
                atom(1, "a", new MappedTranscript.Origin(0, cue, 0, 0, 1, false))), 32));
    }

    @Test
    public void uncoveredTextAndWrongCanonicalTextRejectInsteadOfPublishingAnAcceptedPrefix() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\nab\n", 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        invalid(() -> MappedTranscript.resolved(raw, Collections.singletonList(
                atom(0, "a", new MappedTranscript.Origin(0, cue, 0, 0, 1, false))), 32));
        invalid(() -> MappedTranscript.resolved(raw, Arrays.asList(
                atom(0, "b", new MappedTranscript.Origin(0, cue, 0, 0, 1, false)),
                atom(1, "b", new MappedTranscript.Origin(0, cue, 0, 1, 2, false))), 32));
    }

    @Test
    public void echoCannotBeThePrimaryAndASecondPrimaryCannotPretendToBeAnEcho() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\naa\n", 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        invalid(() -> MappedTranscript.resolved(raw, Collections.singletonList(atom(0, "a",
                new MappedTranscript.Origin(0, cue, 0, 0, 1, true),
                new MappedTranscript.Origin(0, cue, 0, 1, 2, true))), 32));
        invalid(() -> MappedTranscript.resolved(raw, Collections.singletonList(atom(0, "a",
                new MappedTranscript.Origin(0, cue, 0, 0, 1, false),
                new MappedTranscript.Origin(0, cue, 0, 1, 2, false))), 32));
    }

    @Test
    public void partialOriginCannotBorrowAForeignClockOrSplitASurrogatePair() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\nrocket 🚀\n", 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        invalid(() -> new MappedTranscript.Origin(0, cue, 0, 7, 8, false));
        RawTimeline foreign = raw("00:02.000 --> 00:04.000\nrocket 🚀\n", 0);
        invalid(() -> MappedTranscript.resolved(raw, Collections.singletonList(atom(0, "rocket 🚀",
                new MappedTranscript.Origin(0, foreign.getCues().get(0), 0))), 32));
    }

    @Test
    public void legacyConstructorDoesNotSilentlyGainPermissionForPartialOrEchoOrigins() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\nab\n", 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        invalid(() -> new MappedTranscript(raw, Arrays.asList(
                atom(0, "a", new MappedTranscript.Origin(0, cue, 0, 0, 1, false)),
                atom(1, "b", new MappedTranscript.Origin(0, cue, 0, 1, 2, false)))));
    }

    @Test
    public void resolvedOriginsApplyOffsetOnceAndDoNotChangeRawSourceOrVisibility() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\nab\n", -2_000_000);
        byte[] before = raw.copySource();
        RawTimeline.Cue cue = raw.getCues().get(0);
        MappedTranscript mapped = MappedTranscript.resolved(raw, Arrays.asList(
                atom(0, "a", new MappedTranscript.Origin(0, cue, 0, 0, 1, false)),
                atom(1, "b", new MappedTranscript.Origin(0, cue, 0, 1, 2, false))), 32);
        assertEquals(-1_000_000, mapped.getAtoms().get(0).origins.get(0).timingStartUs);
        assertEquals(1_000_000, mapped.getAtoms().get(1).origins.get(0).timingEndUs);
        assertEquals("ab", raw.getCuesAt(0).get(0).textAt(0));
        assertTrue(raw.getCuesAt(1_000_000).isEmpty());
        assertArrayEquals(before, raw.copySource());
    }

    @Test
    public void atomAndReferenceAmplificationAreBothBounded() {
        RawTimeline raw = raw("00:01.000 --> 00:03.000\nab\n", 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        invalid(() -> MappedTranscript.resolved(raw, Arrays.asList(
                atom(0, "a", new MappedTranscript.Origin(0, cue, 0, 0, 1, false)),
                atom(1, "b", new MappedTranscript.Origin(0, cue, 0, 1, 2, false))), 1));
        char[] repeats = new char[65537]; Arrays.fill(repeats, 'a');
        RawTimeline huge = raw("00:01.000 --> 00:03.000\n" + new String(repeats) + "\n", 0);
        List<MappedTranscript.Origin> origins = new ArrayList<>();
        for (int i = 0; i < repeats.length; i++) origins.add(new MappedTranscript.Origin(0, huge.getCues().get(0), 0, i, i + 1, i > 0));
        invalid(() -> MappedTranscript.resolved(huge,
                Collections.singletonList(new MappedTranscript.Atom(0, 0, "a", origins)), 32));
    }

    private static MappedTranscript.Atom atom(int index, String text, MappedTranscript.Origin... origins) {
        return new MappedTranscript.Atom(index, 0, text, Arrays.asList(origins));
    }
    private static String text(MappedTranscript mapped) {
        StringBuilder out = new StringBuilder();
        for (MappedTranscript.Atom atom : mapped.getAtoms()) out.append(atom.text);
        return out.toString();
    }
    private static RawTimeline raw(String cues, long offset) {
        return RawWebvttParser.parse(("WEBVTT\n\n" + cues).getBytes(Charset.forName("UTF-8")), offset,
                new RawTimeline.Limits(1048576, 4096, 32768));
    }
    private static void invalid(Runnable task) {
        try { task.run(); fail("Expected bounded source-range rejection"); }
        catch (IllegalArgumentException expected) { assertEquals("Unsupported or invalid subtitle document", expected.getMessage()); }
    }
}
