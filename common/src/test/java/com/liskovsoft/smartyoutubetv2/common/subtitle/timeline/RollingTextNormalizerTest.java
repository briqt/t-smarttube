package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RollingTextNormalizerTest {
    private static final String ROLLING = "WEBVTT\n\n" +
            "00:00.000 --> 00:03.000\none<00:01.000> two<00:02.000> three\n\n" +
            "00:01.000 --> 00:04.000\n two<00:02.000> three<00:03.000> four\n\n" +
            "00:02.000 --> 00:05.000\n three<00:03.000> four<00:04.000> five\n";

    @Test
    public void verifiedRollingSuffixPrefixProducesOneTextAndAllOriginalReferences() throws Exception {
        RawTimeline raw = vtt(ROLLING);
        MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{7, 7, 7});
        assertEquals("one two three four five", text(result));
        assertEquals(5, result.getAtoms().size());
        assertEquals(1, result.getAtoms().get(0).origins.size());
        assertEquals(2, result.getAtoms().get(1).origins.size());
        assertEquals(3, result.getAtoms().get(2).origins.size());
        assertEquals(2, result.getAtoms().get(3).origins.size());
        assertEquals(1, result.getAtoms().get(4).origins.size());
        for (MappedTranscript.Atom atom : result.getAtoms()) {
            assertEquals(0, atom.flowIndex);
        }
        assertAllReferences(raw, result);
        assertEquals(0, result.getAtoms().get(2).origins.get(0).activeStartUs);
        assertEquals(1_000_000L, result.getAtoms().get(2).origins.get(1).activeStartUs);
        assertEquals(2_000_000L, result.getAtoms().get(2).origins.get(2).activeStartUs);
        // Original display intervals remain distinct; de-rolling is not a player visibility change.
    }

    @Test
    public void asrTextAndTimingAloneDoNotAuthorizeRollingDeduplication() throws Exception {
        RawTimeline raw = vtt(ROLLING);
        for (int[] groups : new int[][]{null, {-1, -1, -1}, {7, 8, 9}}) {
            MappedTranscript result = RollingTextNormalizer.normalize(raw, groups);
            assertEquals(9, result.getAtoms().size());
            assertEquals("one two three two three four three four five", text(result));
            assertEquals(0, result.getAtoms().get(0).flowIndex);
            assertEquals(1, result.getAtoms().get(3).flowIndex);
            assertEquals(2, result.getAtoms().get(6).flowIndex);
            assertAllReferences(raw, result);
        }
    }

    @Test
    public void ordinaryRepeatedDialogueWithDifferentTimesIsPreservedEvenWithHints() throws Exception {
        RawTimeline raw = vtt("WEBVTT\n\n00:00.000 --> 00:02.000\nNo!\n\n" +
                "00:01.000 --> 00:03.000\nNo!\n");
        MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{1, 1});
        assertEquals("No!No!", text(result));
        assertEquals(2, result.getAtoms().size());
        assertEquals(1, result.getAtoms().get(1).flowIndex);
    }

    @Test
    public void ttmlDisplayIntervalsAreNotWordAnchorsForDeduplication() throws Exception {
        RawTimeline raw = RawTtmlParser.parse(bytes("<tt><body><p dur='4s'><span begin='1s' dur='1s'>yes</span></p>" +
                "<p begin='1s' dur='4s'><span dur='1s'>yes</span></p></body></tt>"), 0);
        MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{1, 1});
        assertEquals(2, result.getAtoms().size());
        assertEquals("yesyes", text(result));
        assertEquals(RawTimeline.TimingKind.TTML_INTERVAL, result.getAtoms().get(0).origins.get(0).timingKind);
        assertAllReferences(raw, result);
    }

    @Test
    public void caseWhitespaceAndPunctuationDifferencesDoNotGetFuzzyMatched() throws Exception {
        for (String changed : new String[]{" Two", "two", " two!", " two "}) {
            String document = "WEBVTT\n\n00:00.000 --> 00:03.000\none<00:01.000> two<00:02.000> three\n\n" +
                    "00:01.000 --> 00:04.000\n" + changed + "<00:02.000> three<00:03.000> four\n";
            RawTimeline raw = vtt(document);
            MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{1, 1});
            assertEquals(6, result.getAtoms().size());
            assertAllReferences(raw, result);
        }
    }

    @Test
    public void changedTimestampOrWindowEndDoesNotMergeNearMatches() throws Exception {
        String document = "WEBVTT\n\n00:00.000 --> 00:03.000\none<00:01.000> two<00:02.000> three\n\n" +
                "00:01.001 --> 00:04.000\n two<00:02.000> three<00:03.000> four\n";
        assertEquals(6, RollingTextNormalizer.normalize(vtt(document), new int[]{1, 1}).getAtoms().size());
        document = "WEBVTT\n\n00:00.000 --> 00:03.000\none<00:01.000> two<00:02.000> three\n\n" +
                "00:01.000 --> 00:04.000\n two<00:02.001> three<00:03.000> four\n";
        assertEquals(6, RollingTextNormalizer.normalize(vtt(document), new int[]{1, 1}).getAtoms().size());
    }

    @Test
    public void nonadjacentHintsAndChronologicalReversalDoNotJoinFlows() throws Exception {
        RawTimeline raw = vtt(ROLLING);
        assertEquals(9, RollingTextNormalizer.normalize(raw, new int[]{1, -1, 1}).getAtoms().size());
        List<RawTimeline.Cue> reversed = new ArrayList<>(raw.getCues());
        Collections.reverse(reversed);
        RawTimeline unordered = new RawTimeline(raw.copySource(), reversed);
        assertEquals(9, RollingTextNormalizer.normalize(unordered, new int[]{1, 1, 1}).getAtoms().size());
    }

    @Test
    public void duplicateWholeCueCanAliasButNeverDropsItsSourceOccurrence() throws Exception {
        String cue = "00:00.000 --> 00:02.000\nA<00:01.000>B\n";
        RawTimeline raw = vtt("WEBVTT\n\n" + cue + "\n" + cue);
        MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{0, 0});
        assertEquals("AB", text(result));
        assertEquals(2, result.getAtoms().size());
        assertEquals(2, result.getAtoms().get(0).origins.size());
        assertEquals(2, result.getAtoms().get(1).origins.size());
        assertAllReferences(raw, result);
    }

    @Test
    public void exactTextInsideAnotherCueIsNotARollingPrefix() throws Exception {
        String document = "WEBVTT\n\n00:00.000 --> 00:03.000\none<00:01.000> two<00:02.000> three\n\n" +
                "00:00.500 --> 00:04.000\nX<00:01.000> two<00:02.000> three<00:03.000> four\n";
        assertEquals(7, RollingTextNormalizer.normalize(vtt(document), new int[]{1, 1}).getAtoms().size());
    }

    @Test
    public void emptyCueIsABarrierRatherThanPermissionToMatchAcrossUnknownContent() throws Exception {
        RawTimeline raw = vtt("WEBVTT\n\n00:00.000 --> 00:02.000\nA<00:01.000>B\n\n" +
                "00:00.500 --> 00:01.500\n\n00:00.000 --> 00:02.000\nA<00:01.000>B\n");
        MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{1, 1, 1});
        assertEquals(4, result.getAtoms().size());
        assertAllReferences(raw, result);
    }

    @Test
    public void originalUnicodeTextAndBreakEvidenceArePreserved() throws Exception {
        RawTimeline raw = RawTtmlParser.parse(bytes("<tt><body><p dur='2s'> 你&amp;🙂<br/> A </p></body></tt>"), 0);
        MappedTranscript result = RollingTextNormalizer.normalize(raw, null);
        assertEquals(" 你&🙂\n A ", text(result));
        assertTrue(result.getAtoms().get(1).origins.get(0).lineBreak);
        assertEquals("你&🙂\nA", raw.getCues().get(0).text);
        assertAllReferences(raw, result);
    }

    @Test
    public void sliceReferencesKeepEveryAliasAndNeverInterpolatePartialTiming() throws Exception {
        RawTimeline raw = vtt(ROLLING);
        MappedTranscript result = RollingTextNormalizer.normalize(raw, new int[]{1, 1, 1});
        MappedTranscript.Slice slice = new MappedTranscript.Slice(2, 1, 6);
        assertEquals("three", result.text(slice));
        List<MappedTranscript.Reference> refs = result.references(slice);
        assertEquals(3, refs.size());
        for (MappedTranscript.Reference ref : refs) {
            assertFalse(ref.wholeRun);
            assertEquals("three", raw.getCues().get(ref.origin.cueIndex).mappingText.substring(ref.textStart, ref.textEnd));
            assertEquals(2_000_000L, ref.origin.timingStartUs);
            assertEquals(3_000_000L, ref.origin.timingEndUs);
        }
        assertTrue(result.references(new MappedTranscript.Slice(2, 0, 6)).get(0).wholeRun);
    }

    @Test
    public void immutableListsAndUnicodeSliceBoundsAreChecked() throws Exception {
        MappedTranscript result = RollingTextNormalizer.normalize(vtt("WEBVTT\n\n00:00.000 --> 00:02.000\n🙂X\n"), null);
        immutable(() -> result.getAtoms().clear());
        immutable(() -> result.getAtoms().get(0).origins.clear());
        immutable(() -> result.references(new MappedTranscript.Slice(0, 0, 2)).clear());
        invalid(() -> result.text(new MappedTranscript.Slice(0, 1, 2)));
        invalid(() -> result.text(new MappedTranscript.Slice(0, 0, 1)));
        invalid(() -> result.text(new MappedTranscript.Slice(0, 0, 4)));
        invalid(() -> result.text(new MappedTranscript.Slice(1, 0, 1)));
        invalid(() -> result.references(null));
        assertEquals("🙂", result.text(new MappedTranscript.Slice(0, 0, 2)));
    }

    @Test
    public void unknownHintShapeAndDuplicateSourceOrdinalsFailClosed() throws Exception {
        RawTimeline raw = vtt(ROLLING);
        invalid(() -> RollingTextNormalizer.normalize(null, null));
        invalid(() -> RollingTextNormalizer.normalize(raw, new int[]{1}));
        invalid(() -> RollingTextNormalizer.normalize(raw, new int[]{1, -2, 1}));
        RawTimeline duplicated = new RawTimeline(raw.copySource(), Arrays.asList(raw.getCues().get(0), raw.getCues().get(0)));
        invalid(() -> RollingTextNormalizer.normalize(duplicated, null));
        assertTrue(RollingTextNormalizer.normalize(vtt("WEBVTT\n\n"), null).getAtoms().isEmpty());
    }

    @Test
    public void everyGeneratedSourceRunSurvivesExactlyOnceAsPrimaryOrAlias() {
        Random random = new Random(1241);
        for (int attempt = 0; attempt < 100; attempt++) {
            List<RawTimeline.Cue> cues = new ArrayList<>();
            int count = 2 + random.nextInt(8);
            int width = 2 + random.nextInt(5);
            for (int i = 0; i < count; i++) {
                List<RawTimeline.TextRun> runs = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (int j = 0; j < width; j++) {
                    int start = text.length();
                    text.append(' ').append(i + j);
                    runs.add(new RawTimeline.TextRun(start, text.length(), i * 1_000_000L,
                            (i + width) * 1_000_000L, (i + j) * 1_000_000L, (i + j + 1) * 1_000_000L,
                            RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, false));
                }
                cues.add(new RawTimeline.Cue(i, null, i * 1_000_000L, (i + width) * 1_000_000L,
                        text.toString(), runs, false));
            }
            RawTimeline raw = new RawTimeline(new byte[]{1}, cues);
            int[] groups = new int[count];
            Arrays.fill(groups, 1);
            MappedTranscript result = RollingTextNormalizer.normalize(raw, groups);
            assertEquals(count + width - 1, result.getAtoms().size());
            assertAllReferences(raw, result);
        }
    }

    private static void assertAllReferences(RawTimeline raw, MappedTranscript transcript) {
        boolean[][] seen = new boolean[raw.getCues().size()][];
        for (int i = 0; i < seen.length; i++) {
            seen[i] = new boolean[raw.getCues().get(i).runs.size()];
        }
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            for (MappedTranscript.Origin origin : atom.origins) {
                assertFalse(seen[origin.cueIndex][origin.runIndex]);
                seen[origin.cueIndex][origin.runIndex] = true;
                assertEquals(atom.text, raw.getCues().get(origin.cueIndex).mappingText.substring(origin.textStart, origin.textEnd));
            }
        }
        for (boolean[] cue : seen) {
            for (boolean run : cue) {
                assertTrue(run);
            }
        }
    }

    private static RawTimeline vtt(String document) throws Exception {
        return RawWebvttParser.parse(bytes(document), 0);
    }

    private static byte[] bytes(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    private static String text(MappedTranscript transcript) {
        StringBuilder result = new StringBuilder();
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            result.append(atom.text);
        }
        return result.toString();
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid mapping rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
        }
    }

    private static void immutable(Runnable action) {
        try {
            action.run();
            fail("Expected immutable mapping");
        } catch (UnsupportedOperationException expected) {
            // Source aliases and local atoms remain stable after construction.
        }
    }
}
