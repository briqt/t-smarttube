package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SemanticGroupPlannerTest {
    @Test
    public void titlesInitialsAndDecimalsDoNotBecomeSentences() {
        MappedTranscript transcript = singleFlow(
                "Mr. Smith met Mrs. Li, Ms. Ng, Dr. Wu and Prof. Fox at 3.14. A. Smith left. Done.");
        assertTexts(transcript, 200,
                "Mr. Smith met Mrs. Li, Ms. Ng, Dr. Wu and Prof. Fox at 3.14.", " A. Smith left.", " Done.");
    }

    @Test
    public void compactTitlesAndExplicitAbbreviationsStayTogether() {
        MappedTranscript transcript = singleFlow(
                "Mr.Smith paid 1.25 in the U.S. and U.K. using e.g. cash, i.e. coins. Next.");
        assertTexts(transcript, 200,
                "Mr.Smith paid 1.25 in the U.S. and U.K. using e.g. cash, i.e. coins.", " Next.");
    }

    @Test
    public void repeatedDialogueAndAbbreviationsArePreserved() {
        MappedTranscript transcript = singleFlow("Dr. Lee said go. Dr. Lee said go.");
        assertTexts(transcript, 200, "Dr. Lee said go.", " Dr. Lee said go.");
    }

    @Test
    public void urlEmailAndDomainInternalDotsStayTogether() {
        MappedTranscript transcript = singleFlow(
                "Visit https://example.com/a.b?q=1.2 and mail a.b@example.co.uk or example.org. Done.");
        assertTexts(transcript, 200,
                "Visit https://example.com/a.b?q=1.2 and mail a.b@example.co.uk or example.org.", " Done.");
    }

    @Test
    public void unpunctuatedAsrRemainsOneGroupAcrossRuns() {
        MappedTranscript transcript = singleFlow("we are", " still talking", " with no punctuation");
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200,
                "we are still talking with no punctuation");
        assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, groups.get(0).boundary);
        assertEquals(3, groups.get(0).slices.size());
    }

    @Test
    public void asciiPunctuationNeedsContextRatherThanAnAtomEnd() {
        MappedTranscript transcript = singleFlow("hello.", "world!", "again?", "still");
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200, "hello.world!again?still");
        assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, groups.get(0).boundary);
    }

    @Test
    public void punctuationAbbreviationsAndClosersCanSpanAtoms() {
        MappedTranscript transcript = singleFlow("Dr", ".", " Smith paid 3", ".", "14.?!", "”", " Next", ".");
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200, "Dr. Smith paid 3.14.?!”", " Next.");
        for (SemanticGroupPlanner.Group group : groups) {
            assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, group.boundary);
        }
    }

    @Test
    public void cjkPunctuationDoesNotNeedWhitespace() {
        MappedTranscript transcript = singleFlow("你好？！』再见。接着！");
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200, "你好？！』", "再见。", "接着！");
        for (SemanticGroupPlanner.Group group : groups) {
            assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, group.boundary);
        }
    }

    @Test
    public void closingQuotesAndBracketsStayWithPunctuation() {
        for (String closer : Arrays.asList("\"", "'", ")", "]", "}", "”", "’", "」", "』")) {
            assertTexts(singleFlow("Say." + closer + "Next!"), 200, "Say." + closer, "Next!");
        }
        assertTexts(singleFlow("“Stop?!”)] Next."), 200, "“Stop?!”)]", " Next.");
    }

    @Test
    public void outerSentenceMarksStayWithTheQuotedSentence() {
        assertTexts(singleFlow("Did she say \"Go!\"? Yes."), 200, "Did she say \"Go!\"?", " Yes.");
        assertTexts(singleFlow("He said (\"Stop!\")?! Then left."), 200, "He said (\"Stop!\")?!", " Then left.");
        assertTexts(singleFlow("他说「停！」？然后走了。"), 200, "他说「停！」？", "然后走了。");
    }

    @Test
    public void guillemetsAndBookBracketsAreClosingPunctuation() {
        assertTexts(singleFlow("«Bonjour !» Au revoir."), 200, "«Bonjour !»", " Au revoir.");
        assertTexts(singleFlow("‹Go!› Again."), 200, "‹Go!›", " Again.");
        assertTexts(singleFlow("《Go!》 Again."), 200, "《Go!》", " Again.");
        assertTexts(singleFlow("【Go!】 Again."), 200, "【Go!】", " Again.");
    }

    @Test
    public void whitespaceIsNeverTrimmedOrInserted() {
        List<SemanticGroupPlanner.Group> groups = assertTexts(singleFlow("  Hello. \nNext!  "), 200,
                "  Hello.", " \nNext!", "  ");
        assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, groups.get(2).boundary);
        assertTexts(singleFlow("Hello", "world"), 200, "Helloworld");
    }

    @Test
    public void budgetPrefersTheLastWhitespaceAndKeepsIt() {
        MappedTranscript transcript = singleFlow("one two three");
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 6, "one ", "two ", "three");
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, groups.get(0).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, groups.get(1).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, groups.get(2).boundary);
    }

    @Test
    public void budgetFallsBackToCodePointsAndReconstructsExactly() {
        MappedTranscript transcript = singleFlow("abc", "defgh", "ij");
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 4, "abcd", "efgh", "ij");
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, groups.get(0).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, groups.get(1).boundary);
        assertEquals(2, groups.get(0).slices.size());
    }

    @Test
    public void emojiSurrogatesAreNeverSplitEvenWithBudgetOne() {
        List<SemanticGroupPlanner.Group> groups = assertTexts(singleFlow("😀😃中😀"), 1, "😀", "😃", "中", "😀");
        assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, groups.get(3).boundary);
        assertTexts(singleFlow("😀。😃！"), 2, "😀。", "😃！");
    }

    @Test
    public void punctuationWinsAtTheBudgetOrFlowEnd() {
        List<SemanticGroupPlanner.Group> groups = assertTexts(singleFlow("Go!Next。"), 8, "Go!Next。");
        assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, groups.get(0).boundary);
        groups = assertTexts(singleFlow("Go! Next!"), 3, "Go!", " ", "Nex", "t!");
        assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, groups.get(0).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, groups.get(1).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, groups.get(3).boundary);
    }

    @Test
    public void punctuationRunLongerThanBudgetStillKeepsExactCoverage() {
        MappedTranscript transcript = singleFlow("Hi?!?!\" Next.");
        List<SemanticGroupPlanner.Group> groups = SemanticGroupPlanner.plan(transcript, 2);
        assertPartition(transcript, groups, 2);
        assertEquals(SemanticGroupPlanner.Boundary.BUDGET, groups.get(0).boundary);
    }

    @Test
    public void independentCueFlowsNeverMergeOrDeduplicate() {
        List<RawTimeline.Cue> cues = Arrays.asList(
                new RawTimeline.Cue(0, null, 0, 1000, "Hello"),
                new RawTimeline.Cue(1, null, 1000, 2000, "world"),
                new RawTimeline.Cue(2, null, 2000, 3000, "world"));
        MappedTranscript transcript = RollingTextNormalizer.normalize(raw(cues), null);
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200, "Hello", "world", "world");
        for (SemanticGroupPlanner.Group group : groups) {
            assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, group.boundary);
        }
    }

    @Test
    public void partialSlicesKeepEveryOriginalTimingKindAndWindow() {
        for (RawTimeline.TimingKind kind : RawTimeline.TimingKind.values()) {
            MappedTranscript transcript = mapped(new String[][]{{"First. Second."}}, kind);
            List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200, "First.", " Second.");
            MappedTranscript.Origin origin = transcript.getAtoms().get(0).origins.get(0);
            int cursor = 0;
            for (SemanticGroupPlanner.Group group : groups) {
                MappedTranscript.Slice slice = group.slices.get(0);
                MappedTranscript.Reference reference = transcript.references(slice).get(0);
                assertFalse(reference.wholeRun);
                assertSame(origin, reference.origin);
                assertEquals(kind, reference.origin.timingKind);
                assertEquals(1000, reference.origin.timingStartUs);
                assertEquals(9000, reference.origin.timingEndUs);
                assertEquals(0, reference.origin.activeStartUs);
                assertEquals(10000, reference.origin.activeEndUs);
                assertEquals(cursor, reference.textStart);
                cursor += group.text(transcript).length();
                assertEquals(cursor, reference.textEnd);
            }
            List<SemanticGroupPlanner.Group> budgetGroups = SemanticGroupPlanner.plan(transcript, 3);
            for (SemanticGroupPlanner.Group group : budgetGroups) {
                for (MappedTranscript.Slice slice : group.slices) {
                    MappedTranscript.Reference reference = transcript.references(slice).get(0);
                    assertFalse(reference.wholeRun);
                    assertSame(origin, reference.origin);
                }
            }
        }
    }

    @Test
    public void aWholeRunReferenceRemainsWholeRatherThanAcquiringAClock() {
        MappedTranscript transcript = singleFlow("unpunctuated text");
        MappedTranscript.Slice slice = SemanticGroupPlanner.plan(transcript, 200).get(0).slices.get(0);
        MappedTranscript.Reference reference = transcript.references(slice).get(0);
        assertTrue(reference.wholeRun);
        assertSame(transcript.getAtoms().get(0).origins.get(0), reference.origin);
    }

    @Test
    public void sharedRollingOriginsSurviveSentenceSlices() {
        String text = "Dr. Lee said go. Dr. Lee said go.";
        List<RawTimeline.Cue> cues = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RawTimeline.TextRun run = new RawTimeline.TextRun(0, text.length(), i * 500L, 5000 + i * 500L,
                    1000, 2000, RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, false);
            cues.add(new RawTimeline.Cue(i, null, i * 500L, 5000 + i * 500L, text,
                    Collections.singletonList(run), false));
        }
        MappedTranscript transcript = RollingTextNormalizer.normalize(raw(cues), new int[]{7, 7});
        assertEquals(1, transcript.getAtoms().size());
        List<SemanticGroupPlanner.Group> groups = assertTexts(transcript, 200,
                "Dr. Lee said go.", " Dr. Lee said go.");
        for (SemanticGroupPlanner.Group group : groups) {
            List<MappedTranscript.Reference> references = transcript.references(group.slices.get(0));
            assertEquals(2, references.size());
            for (int i = 0; i < references.size(); i++) {
                MappedTranscript.Reference reference = references.get(i);
                assertFalse(reference.wholeRun);
                assertEquals(i, reference.origin.cueIndex);
                assertEquals(1000, reference.origin.timingStartUs);
                assertEquals(2000, reference.origin.timingEndUs);
            }
        }
    }

    @Test
    public void returnedListsAreImmutableIncludingEmptyInput() {
        MappedTranscript transcript = singleFlow("Hello.");
        List<SemanticGroupPlanner.Group> groups = SemanticGroupPlanner.plan(transcript, 200);
        expectUnsupported(() -> groups.add(groups.get(0)));
        expectUnsupported(() -> groups.set(0, groups.get(0)));
        expectUnsupported(() -> groups.get(0).slices.clear());
        expectUnsupported(() -> groups.get(0).slices.add(new MappedTranscript.Slice(0, 0, 1)));
        List<SemanticGroupPlanner.Group> empty = SemanticGroupPlanner.plan(singleFlow(), 1);
        assertTrue(empty.isEmpty());
        expectUnsupported(() -> empty.add(groups.get(0)));
    }

    @Test
    public void explicitBudgetMustBePositiveAndDocumentBounded() {
        MappedTranscript transcript = singleFlow("text");
        for (int budget : new int[]{0, -1, Integer.MIN_VALUE, RawTimeline.MAX_DOCUMENT_BYTES + 1, Integer.MAX_VALUE}) {
            try {
                SemanticGroupPlanner.plan(transcript, budget);
                fail("Invalid budget accepted");
            } catch (IllegalArgumentException expected) {
                // No oversized threshold silently becomes an unbounded mode.
            }
        }
        try {
            SemanticGroupPlanner.plan(null, 1);
            fail("Null transcript accepted");
        } catch (IllegalArgumentException expected) {
            // Invalid input uses the fixture contract's sanitized error.
        }
        assertTexts(transcript, RawTimeline.MAX_DOCUMENT_BYTES, "text");
    }

    @Test
    public void longPunctuationRunWithTinyBudgetStillPartitionsExactly() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            text.append('.');
        }
        text.append("tail");
        MappedTranscript transcript = singleFlow(text.toString());
        List<SemanticGroupPlanner.Group> groups = SemanticGroupPlanner.plan(transcript, 1);
        assertEquals(text.length(), groups.size());
        assertPartition(transcript, groups, 1);
    }

    @Test
    public void fixedSeedPartitionsPreserveCoverageAndBoundaryDecisions() {
        Random random = new Random(0x53454D414E544943L);
        String[] fragments = {"Mr. Smith", "3.14", " example.org", " A. Fox", " U.S.", "e.g. this",
                "😀", "👩‍💻", "你好。", "？！』", ". ", "?!\"", "\t", "\n", " ", "a", " Dr. Lee"};
        for (int trial = 0; trial < 250; trial++) {
            int flowCount = 1 + random.nextInt(3);
            String[][] whole = new String[flowCount][];
            String[][] partitioned = new String[flowCount][];
            for (int flow = 0; flow < flowCount; flow++) {
                StringBuilder text = new StringBuilder();
                int pieces = 1 + random.nextInt(12);
                for (int piece = 0; piece < pieces; piece++) {
                    text.append(fragments[random.nextInt(fragments.length)]);
                }
                whole[flow] = new String[]{text.toString()};
                partitioned[flow] = randomPartitions(text.toString(), random);
            }
            int budget = 1 + random.nextInt(40);
            MappedTranscript baseline = mapped(whole, RawTimeline.TimingKind.CUE_INTERVAL);
            MappedTranscript split = mapped(partitioned, RawTimeline.TimingKind.CUE_INTERVAL);
            List<SemanticGroupPlanner.Group> expected = SemanticGroupPlanner.plan(baseline, budget);
            List<SemanticGroupPlanner.Group> actual = SemanticGroupPlanner.plan(split, budget);
            assertPartition(baseline, expected, budget);
            assertPartition(split, actual, budget);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).text(baseline), actual.get(i).text(split));
                assertEquals(expected.get(i).boundary, actual.get(i).boundary);
            }
        }
    }

    private static MappedTranscript singleFlow(String... atoms) {
        return mapped(atoms.length == 0 ? new String[0][] : new String[][]{atoms}, RawTimeline.TimingKind.CUE_INTERVAL);
    }

    private static MappedTranscript mapped(String[][] flows, RawTimeline.TimingKind kind) {
        List<RawTimeline.Cue> cues = new ArrayList<>();
        List<MappedTranscript.Atom> atoms = new ArrayList<>();
        for (int flow = 0; flow < flows.length; flow++) {
            StringBuilder text = new StringBuilder();
            List<RawTimeline.TextRun> runs = new ArrayList<>();
            for (String part : flows[flow]) {
                int start = text.length();
                text.append(part);
                runs.add(new RawTimeline.TextRun(start, text.length(), 0, 10000,
                        1000, 9000, kind, false));
            }
            RawTimeline.Cue cue = new RawTimeline.Cue(flow, null, 0, 10000, text.toString(), runs, false);
            cues.add(cue);
            for (int run = 0; run < runs.size(); run++) {
                atoms.add(new MappedTranscript.Atom(atoms.size(), flow, flows[flow][run],
                        Collections.singletonList(new MappedTranscript.Origin(flow, cue, run))));
            }
        }
        return new MappedTranscript(raw(cues), atoms);
    }

    private static RawTimeline raw(List<RawTimeline.Cue> cues) {
        return new RawTimeline("fixture".getBytes(StandardCharsets.UTF_8), cues);
    }

    private static List<SemanticGroupPlanner.Group> assertTexts(MappedTranscript transcript, int budget, String... texts) {
        List<SemanticGroupPlanner.Group> groups = SemanticGroupPlanner.plan(transcript, budget);
        assertPartition(transcript, groups, budget);
        List<String> actual = new ArrayList<>();
        for (SemanticGroupPlanner.Group group : groups) {
            actual.add(group.text(transcript));
        }
        assertEquals(Arrays.asList(texts), actual);
        return groups;
    }

    /** Exact ordered UTF-16 coverage is stronger than comparing concatenated repeated text alone. */
    private static void assertPartition(MappedTranscript transcript, List<SemanticGroupPlanner.Group> groups, int budget) {
        int atomIndex = 0;
        int offset = 0;
        StringBuilder reconstructed = new StringBuilder();
        for (SemanticGroupPlanner.Group group : groups) {
            assertFalse(group.slices.isEmpty());
            int flow = transcript.getAtoms().get(group.slices.get(0).atomIndex).flowIndex;
            String text = group.text(transcript);
            assertTrue(text.codePointCount(0, text.length()) <= budget);
            for (MappedTranscript.Slice slice : group.slices) {
                assertEquals(atomIndex, slice.atomIndex);
                assertEquals(offset, slice.start);
                MappedTranscript.Atom atom = transcript.getAtoms().get(atomIndex);
                assertEquals(flow, atom.flowIndex);
                assertTrue(slice.end > slice.start);
                assertTrue(slice.end <= atom.text.length());
                assertTrue(MappedTranscript.boundary(atom.text, slice.start));
                assertTrue(MappedTranscript.boundary(atom.text, slice.end));
                transcript.references(slice); // Also exercise the contract's range validation.
                offset = slice.end;
                if (offset == atom.text.length()) {
                    atomIndex++;
                    offset = 0;
                }
            }
            reconstructed.append(text);
        }
        assertEquals(transcript.getAtoms().size(), atomIndex);
        assertEquals(0, offset);
        StringBuilder expected = new StringBuilder();
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            expected.append(atom.text);
        }
        assertEquals(expected.toString(), reconstructed.toString());
    }

    private static String[] randomPartitions(String text, Random random) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int cursor = 0; cursor < text.length();) {
            cursor += Character.charCount(text.codePointAt(cursor));
            if (cursor == text.length() || random.nextBoolean()) {
                parts.add(text.substring(start, cursor));
                start = cursor;
            }
        }
        return parts.toArray(new String[0]);
    }

    private static void expectUnsupported(Runnable mutation) {
        try {
            mutation.run();
            fail("Mutable list returned");
        } catch (UnsupportedOperationException expected) {
            // Immutable view required by the planner contract.
        }
    }
}
