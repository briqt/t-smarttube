package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class AsrTranscriptPlannerTest {
    private static final RawTimeline.Limits RAW = new RawTimeline.Limits(1024 * 1024, 8192, 65536);
    private static final SemanticGroupPlanner.Limits GROUPS = new SemanticGroupPlanner.Limits(65536, 65536, 65536);
    // Independent literal oracle. None of these ranges/clocks/texts is computed by the planner.
    private static final String GOLD = "[token1]\ntoken2 token3 token4 token5 token6 token7 token8\n" +
            "token9 token10 token11 token12 token13 token14";
    private static final String[] EVENTS = {"[token1]", "token2", "token3", "token4", "token5", "token6", "token7",
            "token8", "token9", "token10", "token11", "token12", "token13", "token14"};
    private static final int[] LENGTHS = {0, 8, 57, 48, 95, 46};
    private static final int[] BASE = {0, 0, 0, 9, 9, 58};
    private static final long[] STARTS = {100000, 6040000, 6050000, 7480000, 7490000, 10400000};
    private static final long[] ENDS = {6040000, 6050000, 7480000, 7490000, 10400000, 10410000};
    private static final int[][] RUN_ENDS = {{}, {8}, {15, 22, 29, 36, 43, 50, 57}, {48},
            {55, 63, 71, 79, 87, 95}, {46}};
    private static final long[][] WINDOW_ENDS = {{}, {6050000},
            {6250000, 6690000, 6810000, 7049000, 7129000, 7290000, 7480000}, {7490000},
            {8049000, 9010000, 9330000, 9650000, 9930000, 10400000}, {10410000}};

    @Test
    public void independentGoldCoversEveryOriginalUtf16CharacterAndOriginalClock() throws Exception {
        byte[] bytes = fixture();
        RawTimeline raw = parse(bytes, 0);
        List<RawTimeline.Cue> originalCues = raw.getCues();
        List<RawTimeline.TextRun> originalMixedRuns = Arrays.asList(originalCues.get(2).runs.get(0),
                originalCues.get(4).runs.get(0));
        AsrTranscriptPlanner.Analysis result = prepare(raw);
        assertTrue(result.normalized);
        assertEquals(AsrTranscriptPlanner.Reason.PAINT_ON, result.reason);
        assertSame(raw, result.raw);
        assertTrue(result.transcript.isFor(raw));
        assertTrue(result.groups.isFor(result.transcript));
        assertEquals(1, result.transcript.normalizationVersion());
        assertEquals(GOLD, text(result.transcript));
        assertArrayEquals(EVENTS, text(result.transcript).split("\\s+"));
        assertEquals(GOLD, planText(result.groups));
        assertEquals(1, result.groups.getGroups().size());
        assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, result.groups.getGroups().get(0).boundary);
        assertGold(result, 0);
        assertArrayEquals(bytes, raw.copySource());
        assertSame(originalCues, raw.getCues());
        assertSame(originalMixedRuns.get(0), raw.getCues().get(2).runs.get(0));
        assertSame(originalMixedRuns.get(1), raw.getCues().get(4).runs.get(0));
        assertEquals(6, raw.getCues().size());
        int runs = 0;
        for (int c = 0; c < 6; c++) {
            RawTimeline.Cue cue = raw.getCues().get(c);
            assertEquals(STARTS[c], cue.startUs);
            assertEquals(ENDS[c], cue.endUs);
            assertEquals(LENGTHS[c], cue.mappingText.length());
            assertEquals(RUN_ENDS[c].length, cue.runs.size());
            assertEquals("start", cue.webvttSettings.get("align"));
            assertEquals("0%", cue.webvttSettings.get("position"));
            assertEquals(2, cue.webvttSettings.size());
            runs += cue.runs.size();
        }
        assertEquals(16, runs);
        assertMixed(result.transcript, 2, 0, 8, true, "[token1]", 6050000, 6250000);
        assertMixed(result.transcript, 2, 8, 9, false, "\n", 6050000, 6250000);
        assertMixed(result.transcript, 2, 9, 15, false, "token2", 6050000, 6250000);
        assertMixed(result.transcript, 4, 48, 49, false, "\n", 7490000, 8049000);
        assertMixed(result.transcript, 4, 49, 55, false, "token9", 7490000, 8049000);
    }

    @Test
    public void blankSecondDisplayRowInShortHoldsKeepsEveryPaddingCharacterAndClock() {
        String input = small().replace("WEBVTT\n\n", "WEBVTT\n\n00:00.990 --> 00:01.000\nold\n \n\n")
                .replace("00:02.010\nnew line\n\n", "00:02.010\nnew line\n \n\n")
                + "\n00:03.000 --> 00:03.010\nlast line\n \n";
        RawTimeline raw = parse(input);
        AsrTranscriptPlanner.Analysis result = prepare(raw);
        assertTrue(result.normalized);
        assertEquals("old\n new line\n last line\n ", text(result.transcript));
        assertEquals(text(result.transcript), planText(result.groups));
        assertCoverageAndClocks(raw, result.transcript);
        assertArrayEquals(input.getBytes(StandardCharsets.UTF_8), raw.copySource());
        for (String padding : Arrays.asList("\n speaker", "\n \n ")) {
            RawTimeline unsupported = parse(small().replace("00:02.010\nnew line\n\n",
                    "00:02.010\nnew line" + padding + "\n\n"));
            assertPreserved(unsupported, prepare(unsupported));
        }
    }

    @Test
    public void touchingAndTenMillisecondHoldsRemainRealHalfOpenIntervals() throws Exception {
        RawTimeline raw = parse(fixture(), 0);
        AsrTranscriptPlanner.Analysis result = prepare(raw);
        long[] times = {6039999, 6040000, 6049999, 6050000, 7479999, 7480000, 7489999,
                7490000, 10399999, 10400000, 10409999, 10410000};
        int[] expected = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, -1};
        for (int i = 0; i < times.length; i++) {
            List<RawTimeline.Cue> active = raw.getCuesAt(times[i]);
            assertEquals(expected[i] < 0 ? 0 : 1, active.size());
            if (expected[i] >= 0) assertEquals(expected[i], active.get(0).sourceIndex);
            Set<Integer> mappedActive = new HashSet<>();
            for (MappedTranscript.Atom atom : result.transcript.getAtoms()) {
                for (MappedTranscript.Origin origin : atom.origins) {
                    if (origin.activeStartUs <= times[i] && times[i] < origin.activeEndUs) mappedActive.add(origin.cueIndex);
                }
            }
            // Empty c0 remains Raw, but contributes no synthetic atom/word/group.
            assertEquals(expected[i] <= 0 ? Collections.emptySet() : Collections.singleton(expected[i]), mappedActive);
            assertEquals(expected[i] <= 0 ? 0 : 1, result.groups.getGroupsAt(times[i]).size());
        }
        // Echo clocks differ from primary clocks; neither may overwrite the other.
        MappedTranscript.Atom token2 = findAtom(result.transcript, "token2");
        assertEquals(3, token2.origins.size());
        assertEquals(6050000, token2.origins.get(0).timingStartUs);
        assertEquals(7480000, token2.origins.get(1).timingStartUs);
        assertEquals(7490000, token2.origins.get(2).timingStartUs);
    }

    @Test
    public void negativeOffsetPreservesAllClocksIncludingNegativeIntervals() throws Exception {
        AsrTranscriptPlanner.Analysis result = prepare(parse(fixture(), -8000000));
        assertTrue(result.normalized);
        assertEquals(GOLD, text(result.transcript));
        assertGold(result, -8000000);
        assertEquals(-1960000, result.raw.getCues().get(1).startUs);
        assertEquals(1, result.groups.getGroupsAt(-1955000).size());
    }

    @Test
    public void unknownTranslatedConflictingAndTimingOptimizedSourcesStillHavePreserveTranslationPlans() throws Exception {
        RawTimeline raw = parse(fixture(), 0);
        for (CaptionSource source : Arrays.asList(null, source("lang=en"), source("lang=en&kind=other"),
                source("lang=en&kind=asr&tlang=en"), source("lang=en&kind=asr&tlang=fr"),
                source("lang=en&kind=asr&variant=timing-optimized"), source("lang=en&lang=en&kind=asr"),
                source("kind=asr"), source("lang=en&kind=asr&kind=asr"), source("lang=fr&kind=asr"))) {
            assertPreserved(raw, AsrTranscriptPlanner.prepare(raw, source, RAW, GROUPS));
        }
        RawTimeline noLanguage = parse(new String(fixture(), StandardCharsets.UTF_8).replace("Language: en\n", ""));
        assertTrue(prepare(noLanguage).normalized);
        RawTimeline caseLanguage = parse(new String(fixture(), StandardCharsets.UTF_8).replace("Language: en", "Language: EN"));
        assertTrue(prepare(caseLanguage).normalized);
        RawTimeline region = parse(new String(fixture(), StandardCharsets.UTF_8).replace("Language: en", "Language: en-US"));
        assertTrue(prepare(region).normalized);
        RawTimeline nativeRaw = new RawTimeline(new byte[0], raw.getCues(), RAW, raw.getMetadata(),
                RawTimeline.Origin.NATIVE_DISPLAY);
        assertPreserved(nativeRaw, prepare(nativeRaw));
    }

    @Test
    public void actualVoiceLanguageAndClassDeclarationsPreserveEvenAnOtherwiseMatchingProfile() {
        String input = small();
        for (String tag : Arrays.asList("v A", "v B", "lang en", "lang fr", "c.speaker", "c.first.second")) {
            String close = tag.startsWith("c.") ? "c" : tag.substring(0, tag.indexOf(' '));
            RawTimeline raw = parse(input.replace("old\n", "<" + tag + ">old</" + close + ">\n"));
            AsrTranscriptPlanner.Analysis result = prepare(raw);
            assertPreserved(raw, result);
            assertEquals(AsrTranscriptPlanner.Reason.PRESERVED_DECLARATION, result.reason);
        }
        // Same-speaker and changed-speaker declarations both lack a typed scope contract.
        RawTimeline dialogue = parse("WEBVTT\n\n00:00.000 --> 00:03.000\n" +
                "<v A>one<00:01.000> two<00:02.000> three</v>\n\n" +
                "00:01.000 --> 00:04.000\n<v B> two<00:02.000> three<00:03.000> four</v>\n");
        assertPreserved(dialogue, prepare(dialogue));
    }

    @Test
    public void escapedDeclarationLookingTextIsNotMarkupAndNoDeclarationIsNotSameSpeakerEvidence() {
        String input = small().replace("old", "&lt;v A&gt;old");
        AsrTranscriptPlanner.Analysis result = prepare(parse(input));
        assertTrue(result.normalized);
        assertEquals("<v A>old\nnew line\nlast line", text(result.transcript));
        assertTrue(prepare(parse(small())).normalized);
    }

    @Test
    public void brokenHoldLayoutGapReverseEmptyCueAndMissingNewLineWindowDoNotBridge() {
        List<String> inputs = Arrays.asList(
                small().replace("00:02.010\nnew line\n\n", "00:02.010\nother\n\n"),
                small().replace("00:02.010\nnew line\n\n", "00:02.010\nnew\nline\n\n"),
                small().replace("00:02.010\nnew line\n\n", "00:02.010\n\n"),
                small().replace("00:02.000 --> 00:02.010", "00:02.000 --> 00:02.021")
                        .replace("00:02.010 -->", "00:02.021 -->"),
                small().replace("00:02.010 --> 00:03.000", "00:02.011 --> 00:03.000"),
                small().replace("00:02.010 --> 00:03.000", "00:01.900 --> 00:03.000"),
                small().replace("00:02.010 --> 00:03.000", "00:02.010 --> 00:03.000 align:end"),
                small().replace("00:02.000 --> 00:02.010", "00:02.000 --> 00:02.010 align:end"),
                small().replace("last<00:02.500> line", "last line<00:02.500>"));
        for (String input : inputs) {
            RawTimeline raw = parse(input);
            assertPreserved(raw, prepare(raw));
        }
    }

    @Test
    public void untimedOrEdgeTimedBottomLinesBridgeAsrRollingPairs() {
        List<String> inputs = Arrays.asList(
                small().replace("new<00:01.500> line", "new line"),
                small().replace("last<00:02.500> line", "last line"),
                small().replace("new<00:01.500> line", "<00:01.500>new line"));
        for (String input : inputs) {
            RawTimeline raw = parse(input);
            AsrTranscriptPlanner.Analysis analysis = prepare(raw);
            assertTrue(analysis.normalized);
            assertCoverageAndClocks(raw, analysis.transcript);
        }
    }

    @Test
    public void normalizesWebvttStyleHeaderColorTagsAndOneWordLeadCues() {
        String vtt = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "STYLE\n::cue(.colorE5E5E5) { color: #E5E5E5; }\n\n"
                + "00:01.000 --> 00:01.800\n<c.colorCCCCCC>hello</c>\n\n"
                + "00:01.800 --> 00:01.810\nhello\n\n"
                + "00:01.810 --> 00:03.000\nhello\nworld<00:02.400> again\n\n"
                + "00:03.000 --> 00:03.010\nworld again\n\n"
                + "00:03.010 --> 00:04.200\nworld again\ntoday\n";
        RawTimeline raw = RawWebvttParser.parseForNativePlayback(
                vtt.getBytes(StandardCharsets.UTF_8), 0L, RAW);
        AsrTranscriptPlanner.Analysis analysis = prepare(raw);
        assertTrue(analysis.normalized);
        assertCoverageAndClocks(raw, analysis.transcript);
        assertEquals("hello\nworld again\ntoday", text(analysis.transcript));
    }

    @Test
    public void normalizesSpokenLeadDirectAndHoldIntoSingleRichCue() {
        String directTouch = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:01.000 --> 00:02.000\nfirst<00:01.500> second\n\n"
                + "00:02.000 --> 00:03.000\nfirst second\nthird<00:02.500> fourth\n";
        RawTimeline directRaw = parse(directTouch);
        AsrTranscriptPlanner.Analysis direct = prepare(directRaw);
        assertTrue(direct.normalized);
        assertCoverageAndClocks(directRaw, direct.transcript);
        assertEquals("first second\nthird fourth", text(direct.transcript));

        String spokenLeadWithHold = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:01.000 --> 00:01.800\nalpha beta\n\n"
                + "00:01.800 --> 00:01.810\nalpha beta\n\n"
                + "00:01.810 --> 00:02.800\nalpha beta\ngamma<00:02.300> delta\n";
        RawTimeline pairRaw = parse(spokenLeadWithHold);
        AsrTranscriptPlanner.Analysis pair = prepare(pairRaw);
        assertTrue(pair.normalized);
        assertCoverageAndClocks(pairRaw, pair.transcript);
        assertEquals("alpha beta\ngamma delta", text(pair.transcript));
    }

    @Test
    public void normalizesIntermediateCueIntervalAndThreeLineWebvttRolling() {
        String r10Chain = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:01.000 --> 00:02.000\nline1\nline2<00:01.500> mid\n\n"
                + "00:02.000 --> 00:02.010\nline2 mid\n\n"
                + "00:02.010 --> 00:03.000\nline2 mid\nnext\n\n"
                + "00:03.000 --> 00:03.010\nnext\n\n"
                + "00:03.010 --> 00:04.000\nnext\nend<00:03.500> here\n";
        RawTimeline raw = parse(r10Chain);
        AsrTranscriptPlanner.Analysis analysis = prepare(raw);
        assertTrue(analysis.normalized);
        assertCoverageAndClocks(raw, analysis.transcript);
        assertEquals("line1\nline2 mid\nnext\nend here", text(analysis.transcript));

        String threeLine = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:01.000 --> 00:02.000\nL1\nL2\nL3<00:01.500> w\n\n"
                + "00:02.000 --> 00:02.010\nL2\nL3 w\n\n"
                + "00:02.010 --> 00:03.200\nL2\nL3 w\nL4<00:02.600> z\n";
        RawTimeline threeRaw = parse(threeLine);
        AsrTranscriptPlanner.Analysis three = prepare(threeRaw);
        assertTrue(three.normalized);
        assertCoverageAndClocks(threeRaw, three.transcript);
        assertEquals("L1\nL2\nL3 w\nL4 z", text(three.transcript));
    }

    @Test
    public void splitsUnpunctuatedAsrTranscriptAtPauseBoundaries() {
        String pauseVtt = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:01.000 --> 00:03.500\nwelcome everyone to today\n"
                + "<00:01.300>session<00:02.800> right\n\n"
                + "00:03.500 --> 00:03.510\nsession right\n\n"
                + "00:03.510 --> 00:05.000\nsession right\nnow<00:04.000> let us begin immediately\n";
        RawTimeline raw = parse(pauseVtt);
        AsrTranscriptPlanner.Analysis analysis = prepare(raw);
        assertTrue(analysis.normalized);
        assertTrue(analysis.groups.getGroups().size() >= 2);
        assertEquals(SemanticGroupPlanner.Boundary.PAUSE, analysis.groups.getGroups().get(0).boundary);
        assertSingleActiveGroup(analysis.groups, 1_000_000L, 5_000_000L, 100_000L);
    }

    @Test
    public void normalizesProgressiveAndOverlappingTtmlAsrIntoSingleActiveGroupSequence() {
        String ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"en\">\n"
                + "  <body><div>\n"
                + "    <p begin=\"1000ms\" end=\"1500ms\">1</p>\n"
                + "    <p begin=\"1500ms\" end=\"2000ms\">1 2</p>\n"
                + "    <p begin=\"2000ms\" end=\"2600ms\">1 2 3</p>\n"
                + "    <p begin=\"2500ms\" end=\"3300ms\">2 3 4</p>\n"
                + "    <p begin=\"3300ms\" end=\"4200ms\">3 4 5</p>\n"
                + "  </div></body>\n"
                + "</tt>";
        RawTimeline raw = TtmlTimelineParser.parseForNativePlayback(
                ttml.getBytes(StandardCharsets.UTF_8), 0L, RAW);
        AsrTranscriptPlanner.Analysis analysis = prepare(raw);
        assertTrue(analysis.normalized);
        assertEquals(1, analysis.transcript.normalizationVersion());
        assertEquals("1 2 3 4 5", text(analysis.transcript));
        assertSingleActiveGroup(analysis.groups, 900_000L, 4_500_000L, 50_000L);

        String slidingBrTtml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"en\">\n"
                + "  <body><div>\n"
                + "    <p begin=\"1000ms\" end=\"2000ms\">one two<br/>three four</p>\n"
                + "    <p begin=\"1950ms\" end=\"3000ms\">three four<br/>five six</p>\n"
                + "    <p begin=\"2950ms\" end=\"4000ms\">five six<br/>seven eight</p>\n"
                + "  </div></body>\n"
                + "</tt>";
        RawTimeline brRaw = TtmlTimelineParser.parseForNativePlayback(
                slidingBrTtml.getBytes(StandardCharsets.UTF_8), 0L, RAW);
        AsrTranscriptPlanner.Analysis brAnalysis = prepare(brRaw);
        assertTrue(brAnalysis.normalized);
        assertEquals("one two\nthree four\nfive six\nseven eight", text(brAnalysis.transcript));
        assertSingleActiveGroup(brAnalysis.groups, 900_000L, 4_100_000L, 50_000L);
    }

    private static void assertSingleActiveGroup(SemanticGroupPlanner.Plan plan,
                                                long fromUs, long toUs, long stepUs) {
        for (long t = fromUs; t <= toUs; t += stepUs) {
            assertTrue("Expected <= 1 active group at " + t, plan.getGroupsAt(t).size() <= 1);
        }
    }

    @Test
    public void ordinaryLegalRepeatsNeverBecomeOneOccurrence() {
        for (String word : Arrays.asList("No!", "again")) {
            for (String secondStart : Arrays.asList("00:01.000", "00:02.000")) {
                RawTimeline raw = parse("WEBVTT\n\n00:00.000 --> 00:02.000\n" + word +
                        "\n\n" + secondStart + " --> 00:03.000\n" + word + "\n");
                AsrTranscriptPlanner.Analysis result = prepare(raw);
                assertPreserved(raw, result);
                assertEquals(2, result.groups.getGroups().size());
                assertEquals(word, result.groups.getText(0));
                assertEquals(word, result.groups.getText(1));
                assertEquals(0, result.transcript.getAtoms().get(0).origins.get(0).cueIndex);
                assertEquals(1, result.transcript.getAtoms().get(1).origins.get(0).cueIndex);
            }
        }
    }

    @Test
    public void badComponentIsLocalAndOtherExplicitComponentStillNormalizes() {
        String broken = small().replace("00:02.010\nnew line\n\n", "00:02.010\nother\n\n");
        String valid = small().substring("WEBVTT\n\n".length())
                .replace("00:01.", "00:11.").replace("00:02.", "00:12.").replace("00:03.", "00:13.");
        RawTimeline raw = parse(broken + "\n" + valid);
        AsrTranscriptPlanner.Analysis result = prepare(raw);
        assertTrue(result.normalized);
        assertEquals("old\nnew lineothernew line\nlast lineold\nnew line\nlast line", text(result.transcript));
        assertEquals("old\nnew line", result.groups.getText(0));
        assertEquals("other", result.groups.getText(1));
        assertEquals("new line\nlast line", result.groups.getText(2));
        assertEquals("old\nnew line\nlast line", result.groups.getText(3));
        assertEquals(0, result.groups.getFlowIndex(0));
        assertEquals(1, result.groups.getFlowIndex(1));
        assertEquals(2, result.groups.getFlowIndex(2));
        assertEquals(3, result.groups.getFlowIndex(3));
        assertCoverageAndClocks(raw, result.transcript);
    }

    @Test
    public void headAndTailAreOptionalAndSeveralRichHoldPairsFormOneFlow() {
        String extended = small() + "\n00:03.000 --> 00:03.010\nlast line\n\n" +
                "00:03.010 --> 00:04.000\nlast line\nmore<00:03.500> text\n";
        AsrTranscriptPlanner.Analysis chain = prepare(parse(extended));
        assertTrue(chain.normalized);
        assertEquals("old\nnew line\nlast line\nmore text", text(chain.transcript));
        assertEquals(1, chain.groups.getGroups().size());
        String ends = small().replace("WEBVTT\n\n", "WEBVTT\n\n00:00.990 --> 00:01.000\nold\n\n") +
                "\n00:03.000 --> 00:03.010\nlast line\n";
        AsrTranscriptPlanner.Analysis withEnds = prepare(parse(ends));
        assertTrue(withEnds.normalized);
        assertEquals("old\nnew line\nlast line", text(withEnds.transcript));
        assertEquals(0, withEnds.transcript.getAtoms().get(0).origins.get(0).cueIndex);
        assertFalse(withEnds.transcript.getAtoms().get(0).origins.get(0).echo);
        assertCoverageAndClocks(withEnds.raw, withEnds.transcript);
        String badEnds = ends.replace("00:01.000\nold\n\n", "00:01.000\nother\n\n")
                .replace("00:03.010\nlast line\n", "00:03.010\nother\n");
        AsrTranscriptPlanner.Analysis preservedEnds = prepare(parse(badEnds));
        assertTrue(preservedEnds.normalized);
        assertEquals("otherold\nnew line\nlast lineother", text(preservedEnds.transcript));
        assertEquals(3, preservedEnds.groups.getGroups().size());
    }

    @Test
    public void retimedOldRowsKeepIndependentOccurrencesAndSupplementaryUtf16RemainsIntact() {
        RawTimeline raw = parse(small().replace("new line\nlast", "n<00:02.100>ew line\nlast"));
        AsrTranscriptPlanner.Analysis result = prepare(raw);
        // Exact character alignment is not proof that newly timed speech is an old display row.
        assertPreserved(raw, result);
        RawTimeline unicode = parse(small().replace("old", "𐐀").replace("new", "😀").replace("last", "終😀"));
        AsrTranscriptPlanner.Analysis unicodeResult = prepare(unicode);
        assertTrue(unicodeResult.normalized);
        assertEquals("𐐀\n😀 line\n終😀 line", text(unicodeResult.transcript));
        assertCoverageAndClocks(unicode, unicodeResult.transcript);
    }

    @Test
    public void crlfBomHoldThresholdAndSettingsOrderAreInputVariantsNotDifferentSemantics() throws Exception {
        String fixture = new String(fixture(), StandardCharsets.UTF_8);
        for (String input : Arrays.asList(fixture.replace("\n", "\r\n"), "﻿" + fixture,
                fixture.replace("align:start position:0%", "position:0% align:start"))) {
            AsrTranscriptPlanner.Analysis result = prepare(parse(input));
            assertTrue(result.normalized);
            assertEquals(GOLD, text(result.transcript));
            assertGold(result, 0);
        }
        for (String duration : Arrays.asList("001", "010", "020")) {
            RawTimeline raw = parse(small().replace("00:02.010", "00:02." + duration));
            assertTrue(prepare(raw).normalized);
        }
        RawTimeline tooLong = parse(small().replace("00:02.010", "00:02.021"));
        assertPreserved(tooLong, prepare(tooLong));
    }

    @Test
    public void normalizedAtomOrReferenceOverflowFallsBackToWholeDocumentNotPrefix() throws Exception {
        RawTimeline raw = parse(fixture(), 0);
        AsrTranscriptPlanner.Analysis exact = AsrTranscriptPlanner.prepare(raw, direct(),
                new RawTimeline.Limits(1024 * 1024, 6, 16), new SemanticGroupPlanner.Limits(1, 16, 37));
        assertTrue(exact.normalized);
        assertEquals(GOLD, planText(exact.groups));
        assertEquals(16, exact.transcript.getAtoms().size());
        assertEquals(37, exact.groups.getReferences(0).size());
        AsrTranscriptPlanner.Analysis references = AsrTranscriptPlanner.prepare(raw, direct(), RAW,
                new SemanticGroupPlanner.Limits(10, 100, 16));
        assertPreserved(raw, references);
        assertEquals(AsrTranscriptPlanner.Reason.PRESERVED_BUDGET, references.reason);
        // Raw has 5 runs; splitting newline/mixed primary adds two atoms. Preserve still fits 5.
        RawTimeline small = parse(small());
        assertEquals(5, countRuns(small));
        AsrTranscriptPlanner.Analysis atoms = AsrTranscriptPlanner.prepare(small, direct(),
                new RawTimeline.Limits(1024 * 1024, 8192, 5), GROUPS);
        assertPreserved(small, atoms);
        assertEquals(AsrTranscriptPlanner.Reason.PRESERVED_BUDGET, atoms.reason);
        assertArrayEquals(raw.copySource(), references.raw.copySource());
    }

    @Test
    public void hardReferenceCapRejectsAliasAmplificationWithoutDroppingOriginalRuns() {
        // 22,000 primary fragments would each have two distinct display echoes (>65,536 refs).
        StringBuilder input = new StringBuilder("WEBVTT\n\n00:01.000 --> 00:30.000\nold\nx");
        for (int i = 1; i < 22000; i++) input.append('<').append(timestampMs(1000 + i)).append(">x");
        String line = repeat("x", 22000);
        input.append("\n\n00:30.000 --> 00:30.010\n").append(line)
                .append("\n\n00:30.010 --> 00:32.000\n").append(line)
                .append("\nlast<00:31.000> line\n");
        RawTimeline raw = parse(input.toString());
        assertEquals(22003, countRuns(raw));
        AsrTranscriptPlanner.Analysis result = prepare(raw);
        assertPreserved(raw, result);
        assertEquals(AsrTranscriptPlanner.Reason.PRESERVED_BUDGET, result.reason);
    }

    @Test
    public void incompatibleClockKindsAndMissingHoldDoNotImplyContinuity() {
        RawTimeline raw = parse(small());
        List<RawTimeline.Cue> cues = new ArrayList<>(raw.getCues());
        RawTimeline.Cue first = cues.get(0);
        List<RawTimeline.TextRun> runs = new ArrayList<>(first.runs);
        RawTimeline.TextRun run = runs.get(0);
        runs.set(0, new RawTimeline.TextRun(run.textStart, run.textEnd, run.activeStartUs, run.activeEndUs,
                run.timingStartUs, run.timingEndUs, RawTimeline.TimingKind.TTML_INTERVAL, run.lineBreak));
        cues.set(0, new RawTimeline.Cue(0, null, first.startUs, first.endUs, first.mappingText, runs, false));
        RawTimeline mixedClock = new RawTimeline(raw.copySource(), cues, RAW);
        assertPreserved(mixedClock, prepare(mixedClock));
        RawTimeline noHold = parse(small().replace("00:02.000 --> 00:02.010\nnew line\n\n", "")
                .replace("00:02.010 -->", "00:02.000 -->"));
        assertPreserved(noHold, prepare(noHold));
    }

    @Test
    public void budgetsTooSmallEvenForPreserveReturnOriginalAndNoPartialPlan() throws Exception {
        RawTimeline raw = parse(fixture(), 0);
        AsrTranscriptPlanner.Analysis plan = AsrTranscriptPlanner.prepare(raw, source("lang=en"), RAW,
                new SemanticGroupPlanner.Limits(1, 1, 1));
        assertFalse(plan.normalized);
        assertSame(raw, plan.raw);
        assertNotNull(plan.transcript);
        assertNull(plan.groups);
        assertEquals(AsrTranscriptPlanner.Reason.PRESERVED_BUDGET, plan.reason);
        for (RawTimeline.Limits limit : Arrays.asList(new RawTimeline.Limits(1, 8192, 65536),
                new RawTimeline.Limits(1024 * 1024, 1, 65536), new RawTimeline.Limits(1024 * 1024, 8192, 1))) {
            AsrTranscriptPlanner.Analysis result = AsrTranscriptPlanner.prepare(raw, direct(), limit, GROUPS);
            assertSame(raw, result.raw);
            assertFalse(result.normalized);
            assertNull(result.transcript);
            assertNull(result.groups);
            assertEquals(AsrTranscriptPlanner.Reason.PRESERVED_BUDGET, result.reason);
        }
    }

    @Test
    public void candidateBudgetCutsDoNotClaimSentenceRecoveryAndAllGroupsRetainText() {
        String middle = "new " + repeat("word ", 28) + "line";
        String input = small().replace("new<00:01.500> line", "new<00:01.500> " + repeat("word ", 28) + "line")
                .replace("new line", middle);
        AsrTranscriptPlanner.Analysis result = prepare(parse(input));
        assertTrue(result.normalized);
        assertEquals("old\n" + middle + "\nlast line", planText(result.groups));
        assertTrue(result.groups.getGroups().size() > 1);
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, result.groups.getGroups().get(0).boundary);
        assertCoverageAndClocks(result.raw, result.transcript);
        // A low group budget rejects the whole candidate result; 3 independent cues still fit.
        String longer = input.replace(repeat("word ", 28), repeat("word ", 110));
        RawTimeline raw = parse(longer);
        AsrTranscriptPlanner.Analysis fallback = AsrTranscriptPlanner.prepare(raw, direct(), RAW,
                new SemanticGroupPlanner.Limits(3, 1000, 1000));
        assertPreserved(raw, fallback);
    }

    @Test
    public void resolvedMultiGroupWindowsStaySingleAcrossMidCueSplitsHoldsAndSameRunSentences() {
        // 1) Long multi-sentence ASR component with BUDGET cut spanning '\n',
        //    inline <timestamp> boundary, 10ms bridge holds, and 10ms tail hold.
        String budgetPrefix = "alpha " + repeat("word ", 21) + "end"; // ~114 chars; BUDGET cuts after "\nnext "
        String vtt = "WEBVTT\n\n" +
                "00:01.000 --> 00:05.000\n" + budgetPrefix + "\nnext<00:03.000> sentence.\n\n" +
                "00:05.000 --> 00:05.010\nnext sentence.\n\n" +
                "00:05.010 --> 00:08.000\nnext sentence.\nafter<00:06.000> pause. <00:07.000>final part\n\n" +
                "00:08.000 --> 00:08.010\nafter pause. final part\n";
        AsrTranscriptPlanner.Analysis longPlan = prepare(parse(vtt));
        assertTrue(longPlan.normalized);
        SemanticGroupPlanner.Plan plan = longPlan.groups;
        assertEquals(4, plan.getGroups().size());
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, plan.getGroups().get(0).boundary);
        long[] probes = {
                999_999L, 1_000_000L, 1_500_000L, 2_999_999L,
                3_000_000L, 5_005_000L, 5_010_000L,
                6_999_999L, 7_000_000L, 8_005_000L, 8_010_000L
        };
        assertEquals(Collections.emptyList(), plan.getGroupsAt(probes[0]));
        assertEquals(Collections.emptyList(), plan.getGroupsAt(probes[probes.length - 1]));
        for (long t = 1_000_000L; t < 8_010_000L; t += 100_000L) {
            assertEquals("Expected single active group at " + t, 1, plan.getGroupsAt(t).size());
        }
        assertEquals(Collections.singletonList(0), plan.getGroupsAt(1_000_000L));
        assertEquals(Collections.singletonList(1), plan.getGroupsAt(3_000_000L));
        assertEquals(Collections.singletonList(1), plan.getGroupsAt(5_005_000L)); // 10ms bridge hold stays on G1
        assertEquals(Collections.singletonList(2), plan.getGroupsAt(5_010_000L));
        assertEquals(Collections.singletonList(3), plan.getGroupsAt(7_000_000L)); // Space skipped, jumps cleanly on "final"
        assertEquals(Collections.singletonList(3), plan.getGroupsAt(8_005_000L)); // 10ms tail hold stays on G3
        assertEquals(3_000_000L, plan.getNextStartUs(1, 2_000_000L));
        assertEquals(Long.MAX_VALUE, plan.getNextStartUs(0, 5_010_000L)); // Echo in cue 2 must not requeue G0

        // 2) Two sentences inside a single TextRun without inline timestamp + head==false + trailing whitespace "\n "
        String sameRunVtt = "WEBVTT\n\n" +
                "00:10.000 --> 00:12.000\nFirst part. Second part\nmid<00:11.500> line\n\n" +
                "00:12.000 --> 00:12.010\nmid line\n\n" +
                "00:12.010 --> 00:14.000\nmid line\nlast<00:13.000> line.\n\n" +
                "00:14.000 --> 00:14.010\nlast line.\n \n";
        AsrTranscriptPlanner.Analysis sameRun = prepare(parse(sameRunVtt));
        assertTrue(sameRun.normalized);
        SemanticGroupPlanner.Plan sameRunPlan = sameRun.groups;
        assertEquals(3, sameRunPlan.getGroups().size());
        assertEquals("First part.", sameRunPlan.getText(0));
        assertEquals(" Second part\nmid line\nlast line.", sameRunPlan.getText(1));
        assertEquals("\n ", sameRunPlan.getText(2));
        long switchUs = sameRunPlan.getNextStartUs(1, 10_000_000L);
        assertTrue("Split boundary must lie strictly inside (10s, 11.5s)", switchUs > 10_000_000L && switchUs < 11_500_000L);
        assertEquals(Collections.singletonList(0), sameRunPlan.getGroupsAt(10_000_000L));
        assertEquals(Collections.singletonList(0), sameRunPlan.getGroupsAt(switchUs - 1));
        assertEquals(Collections.singletonList(1), sameRunPlan.getGroupsAt(switchUs));
        assertEquals(Collections.singletonList(1), sameRunPlan.getGroupsAt(14_005_000L)); // Pure-whitespace G2 never steals tail hold
        assertEquals(Collections.emptyList(), sameRunPlan.getGroupsAt(14_010_000L));
        assertEquals(Long.MAX_VALUE, sameRunPlan.getNextStartUs(2, 10_000_000L));

        // 3) Separate components with silent gap stay separated (no gap bridging)
        String twoFlowsVtt = sameRunVtt + "\n" +
                "00:20.000 --> 00:22.000\nlead\nnext<00:21.000> flow\n\n" +
                "00:22.000 --> 00:22.010\nnext flow\n\n" +
                "00:22.010 --> 00:24.000\nnext flow\nsecond.<00:23.000> third\n";
        AsrTranscriptPlanner.Analysis twoFlows = prepare(parse(twoFlowsVtt));
        assertTrue(twoFlows.normalized);
        assertEquals(Collections.emptyList(), twoFlows.groups.getGroupsAt(17_000_000L));
    }

    @Test
    public void emptySourceAndImmutableOwnedOutputsAreNotSyntheticEvents() {
        RawTimeline empty = parse("WEBVTT\n\n");
        AsrTranscriptPlanner.Analysis result = prepare(empty);
        assertFalse(result.normalized);
        assertTrue(result.transcript.getAtoms().isEmpty());
        assertTrue(result.groups.getGroups().isEmpty());
        AsrTranscriptPlanner.Analysis normalized = prepare(parse(small()));
        try { normalized.transcript.getAtoms().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { normalized.transcript.getAtoms().get(0).origins.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { normalized.groups.getGroups().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        assertFalse(normalized.transcript.isFor(parse(small())));
    }

    private static void assertGold(AsrTranscriptPlanner.Analysis result, long offset) {
        int[][] coverage = new int[6][];
        for (int c = 0; c < coverage.length; c++) coverage[c] = new int[LENGTHS[c]];
        int position = 0;
        for (MappedTranscript.Atom atom : result.transcript.getAtoms()) {
            assertEquals(1, atom.flowIndex); // c0 empty, then the optional c1 head hold.
            assertEquals(GOLD.substring(position, position + atom.text.length()), atom.text);
            List<MappedTranscript.Reference> refs = result.transcript.references(
                    new MappedTranscript.Slice(atom.index, 0, atom.text.length()));
            assertEquals(atom.origins.size(), refs.size());
            for (int o = 0; o < atom.origins.size(); o++) {
                MappedTranscript.Origin origin = atom.origins.get(o);
                int c = origin.cueIndex, r = origin.runIndex;
                assertEquals(c, origin.sourceIndex);
                assertEquals(STARTS[c] + offset, origin.activeStartUs);
                assertEquals(ENDS[c] + offset, origin.activeEndUs);
                assertEquals((r == 0 ? STARTS[c] : WINDOW_ENDS[c][r - 1]) + offset, origin.timingStartUs);
                assertEquals(WINDOW_ENDS[c][r] + offset, origin.timingEndUs);
                assertEquals(c == 2 || c == 4 ? RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW :
                        RawTimeline.TimingKind.CUE_INTERVAL, origin.timingKind);
                assertFalse(origin.lineBreak);
                assertEquals(o > 0, origin.echo);
                assertEquals(origin.textStart, refs.get(o).textStart);
                assertEquals(origin.textEnd, refs.get(o).textEnd);
                int runStart = r == 0 ? 0 : RUN_ENDS[c][r - 1];
                assertEquals(origin.textStart == runStart && origin.textEnd == RUN_ENDS[c][r], refs.get(o).wholeRun);
                for (int p = origin.textStart; p < origin.textEnd; p++) {
                    assertEquals(position + p - origin.textStart, BASE[c] + p);
                    assertEquals(c == 3 || c == 5 || c == 2 && p < 8 || c == 4 && p < 48, origin.echo);
                    coverage[c][p]++;
                }
            }
            position += atom.text.length();
        }
        assertEquals(104, position);
        for (int[] cue : coverage) for (int count : cue) assertEquals(1, count);
    }

    private static void assertMixed(MappedTranscript transcript, int cue, int from, int to, boolean echo,
                                    String text, long start, long end) {
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            for (MappedTranscript.Origin origin : atom.origins) {
                if (origin.cueIndex == cue && origin.textStart == from && origin.textEnd == to) {
                    assertEquals(text, atom.text);
                    assertEquals(0, origin.runIndex);
                    assertEquals(echo, origin.echo);
                    assertEquals(start, origin.timingStartUs);
                    assertEquals(end, origin.timingEndUs);
                    assertFalse(origin.wholeRun);
                    return;
                }
            }
        }
        fail("Missing exact mixed-run fragment");
    }

    private static void assertCoverageAndClocks(RawTimeline raw, MappedTranscript transcript) {
        List<int[]> seen = new ArrayList<>();
        for (RawTimeline.Cue cue : raw.getCues()) seen.add(new int[cue.mappingText.length()]);
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            assertTrue(MappedTranscript.boundary(atom.text, 0));
            for (int i = 0; i < atom.origins.size(); i++) {
                MappedTranscript.Origin origin = atom.origins.get(i);
                RawTimeline.Cue cue = raw.getCues().get(origin.cueIndex);
                RawTimeline.TextRun run = cue.runs.get(origin.runIndex);
                assertEquals(i > 0, origin.echo);
                assertEquals(atom.text, cue.mappingText.substring(origin.textStart, origin.textEnd));
                assertTrue(MappedTranscript.boundary(cue.mappingText, origin.textStart));
                assertTrue(MappedTranscript.boundary(cue.mappingText, origin.textEnd));
                assertEquals(run.activeStartUs, origin.activeStartUs);
                assertEquals(run.activeEndUs, origin.activeEndUs);
                assertEquals(run.timingStartUs, origin.timingStartUs);
                assertEquals(run.timingEndUs, origin.timingEndUs);
                assertEquals(run.timingKind, origin.timingKind);
                assertEquals(run.lineBreak, origin.lineBreak);
                for (int p = origin.textStart; p < origin.textEnd; p++) seen.get(origin.cueIndex)[p]++;
            }
        }
        for (int[] cue : seen) for (int count : cue) assertEquals(1, count);
    }

    private static void assertPreserved(RawTimeline raw, AsrTranscriptPlanner.Analysis result) {
        assertFalse(result.normalized);
        assertSame(raw, result.raw);
        assertTrue(result.transcript.isFor(raw));
        assertTrue(result.groups.isFor(result.transcript));
        assertEquals(0, result.transcript.normalizationVersion());
        StringBuilder expected = new StringBuilder();
        for (RawTimeline.Cue cue : raw.getCues()) expected.append(cue.mappingText);
        assertEquals(expected.toString(), text(result.transcript));
        assertEquals(expected.toString(), planText(result.groups));
        for (MappedTranscript.Atom atom : result.transcript.getAtoms()) {
            assertEquals(1, atom.origins.size());
            assertFalse(atom.origins.get(0).echo);
            assertTrue(atom.origins.get(0).wholeRun);
            assertEquals(atom.origins.get(0).cueIndex, atom.flowIndex);
        }
        assertCoverageAndClocks(raw, result.transcript);
    }

    private static MappedTranscript.Atom findAtom(MappedTranscript transcript, String text) {
        for (MappedTranscript.Atom atom : transcript.getAtoms()) if (atom.text.equals(text)) return atom;
        throw new AssertionError("Missing literal atom");
    }

    private static String text(MappedTranscript transcript) {
        StringBuilder result = new StringBuilder();
        for (MappedTranscript.Atom atom : transcript.getAtoms()) result.append(atom.text);
        return result.toString();
    }

    private static String planText(SemanticGroupPlanner.Plan plan) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < plan.getGroups().size(); i++) result.append(plan.getText(i));
        return result.toString();
    }

    private static int countRuns(RawTimeline raw) {
        int result = 0;
        for (RawTimeline.Cue cue : raw.getCues()) result += cue.runs.size();
        return result;
    }

    private static String timestampMs(int ms) {
        int seconds = ms / 1000, millis = ms % 1000;
        return "00:" + (seconds < 10 ? "0" : "") + seconds + "." +
                (millis < 10 ? "00" : millis < 100 ? "0" : "") + millis;
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }

    private static AsrTranscriptPlanner.Analysis prepare(RawTimeline raw) {
        return AsrTranscriptPlanner.prepare(raw, direct(), RAW, GROUPS);
    }

    private static CaptionSource direct() { return source("lang=en&kind=asr"); }

    private static CaptionSource source(String query) {
        MediaSubtitle subtitle = (MediaSubtitle) Proxy.newProxyInstance(MediaSubtitle.class.getClassLoader(),
                new Class<?>[]{MediaSubtitle.class}, (proxy, method, args) -> {
                    if ("getBaseUrl".equals(method.getName())) return "https://example.invalid/timedtext?" + query;
                    return null;
                });
        return CaptionSourceClassifier.classify(subtitle);
    }

    private static RawTimeline parse(String text) { return parse(text.getBytes(StandardCharsets.UTF_8), 0); }

    private static RawTimeline parse(byte[] bytes, long offset) { return RawWebvttParser.parse(bytes, offset, RAW); }

    private static String small() {
        return "WEBVTT\n\n00:01.000 --> 00:02.000\nold\nnew<00:01.500> line\n\n" +
                "00:02.000 --> 00:02.010\nnew line\n\n" +
                "00:02.010 --> 00:03.000\nnew line\nlast<00:02.500> line\n";
    }

    private static byte[] fixture() throws Exception {
        try (InputStream input = AsrTranscriptPlannerTest.class.getResourceAsStream("/subtitle/rolling_settings_synthetic.vtt")) {
            assertNotNull(input);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) result.write(buffer, 0, length);
            return result.toByteArray();
        }
    }
}
