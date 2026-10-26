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
        assertPreserved(region, prepare(region));
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
                small().replace("new<00:01.500> line", "new line"),
                small().replace("last<00:02.500> line", "last line"),
                small().replace("last<00:02.500> line", "last line<00:02.500>"),
                small().replace("new<00:01.500> line", "<00:01.500>new line"));
        for (String input : inputs) {
            RawTimeline raw = parse(input);
            assertPreserved(raw, prepare(raw));
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
