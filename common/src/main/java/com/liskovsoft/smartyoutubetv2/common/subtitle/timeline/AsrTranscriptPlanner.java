package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, source-only recognition of rolling WebVTT and TTML ASR streams. No fetching,
 * display policy, acoustic speaker inference, or translation/cache identity is supplied here.
 * The caller must bind {@code source} uniquely to this exact Raw document before calling.
 */
public final class AsrTranscriptPlanner {
    private static final int MAX_REFERENCES = 65536;
    private static final int CANDIDATE_CODE_POINTS = 120;
    private static final long PAUSE_GAP_US = 800_000L;
    private static final long STALLED_WORD_US = 1_200_000L;
    private static final long MAX_HOLD_US = 20_000L;
    private static final long MIN_SPOKEN_LEAD_US = 150_000L;
    private static final long MAX_ADJACENT_GAP_US = 500_000L;
    // Inspect original markup, not decoded mappingText: &lt;v is ordinary text.
    private static final Pattern VTT_TAG = Pattern.compile("<([^<>\\n]+)>");
    private static final Pattern TTML_AGENT_ATTR = Pattern.compile("\\bttm:(?:agent|role)\\b");

    public enum Reason {
        PAINT_ON,
        PRESERVED_SOURCE,
        PRESERVED_LANGUAGE,
        PRESERVED_DECLARATION,
        PRESERVED_FORMAT,
        PRESERVED_STRUCTURE,
        PRESERVED_BUDGET
    }

    public static final class Analysis {
        /** Always retained, including when caller budgets cannot even represent a preserve plan. */
        public final RawTimeline raw;
        /** Null only if the supplied admission/atom budget cannot represent the original. */
        public final MappedTranscript transcript;
        /**
         * Null only on budget failure that cannot represent even the independent-cue preserve
         * plan. This is not an instruction to stop translation: use the original Raw through
         * the caller's existing preserve path. No partial normalized plan is ever published.
         */
        public final SemanticGroupPlanner.Plan groups;
        public final boolean normalized;
        public final Reason reason;

        private Analysis(RawTimeline raw, MappedTranscript transcript, SemanticGroupPlanner.Plan groups,
                         boolean normalized, Reason reason) {
            this.raw = raw;
            this.transcript = transcript;
            this.groups = groups;
            this.normalized = normalized;
            this.reason = reason;
        }
    }

    private AsrTranscriptPlanner() {
    }

    /**
     * Pure preparation, suitable for a download/decoder worker. Missing or uncertain source
     * evidence only disables destructive normalization, never AI translation. Undeclared voice
     * remains undeclared. BUDGET boundaries in the candidate plan are not complete sentences.
     */
    public static Analysis prepare(RawTimeline raw, CaptionSource source, RawTimeline.Limits limits,
                                   SemanticGroupPlanner.Limits groups) {
        if (raw == null || limits == null || groups == null) throw RawTimeline.invalid();
        byte[] bytes = raw.copySource();
        int runCount = 0;
        for (RawTimeline.Cue cue : raw.getCues()) {
            if (cue.runs.size() > limits.textRuns - runCount) {
                return new Analysis(raw, null, null, false, Reason.PRESERVED_BUDGET);
            }
            runCount += cue.runs.size();
        }
        if (bytes.length > limits.documentBytes || raw.getCues().size() > limits.cues) {
            return new Analysis(raw, null, null, false, Reason.PRESERVED_BUDGET);
        }
        MappedTranscript preserved = MappedTranscript.fromRaw(raw, limits.textRuns);
        if (raw.getOrigin() != RawTimeline.Origin.DOCUMENT || source == null ||
                source.getTextKind() != CaptionSource.TextKind.ASR_CANDIDATE ||
                source.getTranslationKind() != CaptionSource.TranslationKind.DIRECT ||
                source.getSourceLanguage() == null || source.getTranslationLanguage() != null ||
                !source.getProblems().isEmpty() || source.hasTimingOptimizedVariant()) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_SOURCE);
        }
        if (!compatibleLanguage(raw.getMetadata().get("Language"), source.getSourceLanguage())) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_LANGUAGE);
        }
        String document;
        try {
            document = RawTimeline.decodeUtf8(bytes, limits);
        } catch (IllegalArgumentException unsupported) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_FORMAT);
        }
        int first = document.startsWith("\ufeff") ? 1 : 0;
        if (isWebvtt(document, first)) {
            if (hasUnsupportedVttDeclaration(document)) {
                return preserve(raw, preserved, groups, Reason.PRESERVED_DECLARATION);
            }
            return prepareWebvtt(raw, preserved, limits, groups);
        }
        if (isTtml(document, first)) {
            if ("true".equals(raw.getMetadata().get("AgentDeclared")) || TTML_AGENT_ATTR.matcher(document).find()) {
                return preserve(raw, preserved, groups, Reason.PRESERVED_DECLARATION);
            }
            return prepareTtml(raw, preserved, limits, groups);
        }
        return preserve(raw, preserved, groups, Reason.PRESERVED_FORMAT);
    }

    private static boolean isWebvtt(String document, int first) {
        return document.startsWith("WEBVTT", first) && document.length() > first + 6 &&
                " \t\r\n".indexOf(document.charAt(first + 6)) >= 0;
    }

    private static boolean isTtml(String document, int first) {
        int pos = first;
        while (pos < document.length() && " \t\r\n".indexOf(document.charAt(pos)) >= 0) {
            pos++;
        }
        return document.startsWith("<?xml", pos) || document.startsWith("<tt", pos);
    }

    private static boolean compatibleLanguage(String headerLanguage, String sourceLanguage) {
        if (headerLanguage == null) {
            return true;
        }
        if (headerLanguage.equalsIgnoreCase(sourceLanguage)) {
            return true;
        }
        String headerPrimary = primaryLanguage(headerLanguage);
        String sourcePrimary = primaryLanguage(sourceLanguage);
        return headerPrimary.length() >= 2 && headerPrimary.equalsIgnoreCase(sourcePrimary);
    }

    private static String primaryLanguage(String tag) {
        int dash = tag.indexOf('-');
        int underscore = tag.indexOf('_');
        int cut = dash < 0 ? underscore : underscore < 0 ? dash : Math.min(dash, underscore);
        return cut < 0 ? tag : tag.substring(0, cut);
    }

    private static boolean hasUnsupportedVttDeclaration(String document) {
        Matcher matcher = VTT_TAG.matcher(document);
        while (matcher.find()) {
            String tag = matcher.group(1);
            if (tag.isEmpty()) continue;
            char first = tag.charAt(0);
            if (first >= '0' && first <= '9') continue;
            String body = first == '/' ? tag.substring(1) : tag;
            if (body.equals("v") || body.startsWith("v ") || body.startsWith("v\t")
                    || body.equals("lang") || body.startsWith("lang ") || body.startsWith("lang\t")) {
                return true;
            }
            if (body.startsWith("c.") && !isSupportedAsrStyleClass(body.substring(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupportedAsrStyleClass(String classes) {
        if (classes.isEmpty()) return false;
        int start = 0;
        while (start <= classes.length()) {
            int dot = classes.indexOf('.', start);
            String segment = dot < 0 ? classes.substring(start) : classes.substring(start, dot);
            if (!isAsrStyleToken(segment)) return false;
            if (dot < 0) return true;
            start = dot + 1;
        }
        return false;
    }

    private static boolean isAsrStyleToken(String token) {
        if (token.startsWith("color")) {
            int hexLen = token.length() - 5;
            if (hexLen < 3 || hexLen > 8) return false;
            for (int i = 5; i < token.length(); i++) {
                char c = token.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) return false;
            }
            return true;
        }
        if (token.startsWith("bg_") && token.length() > 3) {
            for (int i = 3; i < token.length(); i++) {
                char c = token.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_';
                if (!ok) return false;
            }
            return true;
        }
        return false;
    }

    private static Analysis prepareWebvtt(RawTimeline raw, MappedTranscript preserved,
                                          RawTimeline.Limits limits, SemanticGroupPlanner.Limits groups) {
        List<RawTimeline.Cue> cues = raw.getCues();
        int[] split = new int[cues.size()];
        for (int i = 0; i < split.length; i++) split[i] = rollingSplit(cues.get(i));
        Builder output = new Builder(raw, limits.textRuns, Math.min(MAX_REFERENCES, groups.references));
        boolean normalized = false;
        try {
            for (int cue = 0; cue < cues.size();) {
                int richStart = -1;
                boolean headLead = false;
                boolean headHold = false;
                if (split[cue] >= 0) {
                    richStart = cue;
                } else if (cue + 2 < cues.size() && split[cue + 2] >= 0
                        && spokenLeadForHold(cues, cue, cues.get(cue + 1), cues.get(cue + 2), split[cue + 2])) {
                    richStart = cue + 2;
                    headLead = true;
                    headHold = true;
                } else if (cue + 1 < cues.size() && split[cue + 1] >= 0) {
                    if (head(cues.get(cue), cues.get(cue + 1), split[cue + 1])) {
                        richStart = cue + 1;
                        headHold = true;
                    } else if (spokenLeadDirect(cues, cue, cues.get(cue + 1), split[cue + 1])) {
                        richStart = cue + 1;
                        headLead = true;
                    }
                }
                if (richStart < 0) {
                    output.preserve(cue++);
                    continue;
                }
                int richEnd = richStart;
                while (link(cues, split, richEnd)) richEnd += 2;
                int end = richEnd + 1;
                boolean tail = end < cues.size() && tail(cues.get(richEnd), split[richEnd], cues.get(end));
                if (tail) end++;
                if (richEnd == richStart && !headLead) {
                    output.preserve(cue++);
                    continue;
                }
                List<MappedTranscript.Atom> component;
                try {
                    component = component(raw, richStart, richEnd, headLead, headHold, tail, split,
                            output.atoms.size(), cue, limits.textRuns - output.atoms.size(),
                            output.maxReferences - output.references);
                } catch (Unresolved unsupported) {
                    while (cue < end) output.preserve(cue++);
                    continue;
                }
                output.append(component);
                normalized = true;
                cue = end;
            }
            if (!normalized) return preserve(raw, preserved, groups, Reason.PRESERVED_STRUCTURE);
            MappedTranscript transcript = MappedTranscript.resolved(raw, output.atoms, limits.textRuns);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.candidates(
                    transcript, CANDIDATE_CODE_POINTS, PAUSE_GAP_US, STALLED_WORD_US, groups);
            return new Analysis(raw, transcript, plan, true, Reason.PAINT_ON);
        } catch (Budget exceeded) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_BUDGET);
        } catch (IllegalArgumentException invalidMappingOrPlan) {
            // Constructors/planner reject the entire result, with no source-bearing diagnostics.
            return preserve(raw, preserved, groups, Reason.PRESERVED_BUDGET);
        }
    }

    private static Analysis prepareTtml(RawTimeline raw, MappedTranscript preserved,
                                        RawTimeline.Limits limits, SemanticGroupPlanner.Limits groups) {
        List<RawTimeline.Cue> cues = raw.getCues();
        for (RawTimeline.Cue cue : cues) {
            for (RawTimeline.TextRun run : cue.runs) {
                if (run.timingKind != RawTimeline.TimingKind.TTML_INTERVAL
                        && run.timingKind != RawTimeline.TimingKind.CUE_INTERVAL) {
                    return preserve(raw, preserved, groups, Reason.PRESERVED_STRUCTURE);
                }
            }
        }
        int maxRefs = Math.min(MAX_REFERENCES, groups.references);
        List<Unit> flowUnits = new ArrayList<>();
        RawTimeline.Cue prevCue = null;
        String prevText = null;
        List<Unit> prevUnitRefs = Collections.emptyList();
        int currentFlow = 0;
        boolean hasEcho = false;
        boolean hasTemporalOverlap = false;
        int totalUnitChars = 0;
        int approxRefs = 0;

        try {
            for (int cueIndex = 0; cueIndex < cues.size(); cueIndex++) {
                RawTimeline.Cue curr = cues.get(cueIndex);
                String currText = curr.mappingText;
                if (currText.isEmpty()) continue;

                boolean matched = false;
                if (prevCue != null && curr.startUs >= prevCue.startUs) {
                    boolean overlapping = curr.startUs < prevCue.endUs;
                    boolean adjacent = curr.startUs - prevCue.endUs <= MAX_ADJACENT_GAP_US;
                    if (overlapping) {
                        hasTemporalOverlap = true;
                    }
                    if (adjacent) {
                        int[] match = findTtmlEchoMatch(prevText, currText);
                        if (match != null && applyTtmlEchoMatch(
                                flowUnits, prevUnitRefs, cueIndex, currText, match[0], match[1])) {
                            hasEcho = true;
                            matched = true;
                            int addedLen = currText.length() - match[1];
                            List<Unit> nextRefs = collectTailUnitRefs(prevUnitRefs, match[0]);
                            if (addedLen > 0) {
                                Unit added = new Unit(currentFlow, currText.substring(match[1]));
                                added.add(cueIndex, match[1]);
                                flowUnits.add(added);
                                nextRefs.add(added);
                                totalUnitChars += addedLen;
                                approxRefs++;
                            }
                            approxRefs += nextRefs.size();
                            if (totalUnitChars > limits.textRuns * 128 || approxRefs > maxRefs * 2) {
                                throw new Budget();
                            }
                            prevUnitRefs = nextRefs;
                            prevCue = curr;
                            prevText = currText;
                        }
                    }
                }
                if (!matched) {
                    currentFlow = cueIndex;
                    Unit unit = new Unit(currentFlow, currText);
                    unit.add(cueIndex, 0);
                    flowUnits.add(unit);
                    prevUnitRefs = new ArrayList<>(1);
                    prevUnitRefs.add(unit);
                    prevCue = curr;
                    prevText = currText;
                    totalUnitChars += currText.length();
                    approxRefs++;
                }
            }
            if (!hasEcho && !hasTemporalOverlap) {
                return preserve(raw, preserved, groups, Reason.PRESERVED_STRUCTURE);
            }
            Builder output = new Builder(raw, limits.textRuns, maxRefs);
            output.append(emitAtoms(raw, flowUnits, 0, limits.textRuns, maxRefs));
            MappedTranscript transcript = MappedTranscript.resolved(raw, output.atoms, limits.textRuns);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.candidates(
                    transcript, CANDIDATE_CODE_POINTS, PAUSE_GAP_US, STALLED_WORD_US, groups);
            return new Analysis(raw, transcript, plan, true, Reason.PAINT_ON);
        } catch (Budget | Unresolved exceeded) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_BUDGET);
        } catch (IllegalArgumentException invalidMappingOrPlan) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_BUDGET);
        }
    }

    private static int[] findTtmlEchoMatch(String prev, String curr) {
        if (curr.equals(prev)) {
            return new int[]{0, curr.length()};
        }
        int prevLen = prev.length();
        if (curr.length() > prevLen && curr.startsWith(prev)
                && MappedTranscript.boundary(curr, prevLen)
                && isPrefixAccumulationBoundary(prev, curr, prevLen)) {
            return new int[]{0, prevLen};
        }
        int[] lineMatch = matchLineSuffixToPrefix(prev, curr);
        if (lineMatch != null) {
            return lineMatch;
        }
        return matchWordBoundaryOverlap(prev, curr);
    }

    private static boolean isPrefixAccumulationBoundary(String prev, String curr, int boundary) {
        char lastPrev = prev.charAt(prev.length() - 1);
        char nextCurr = curr.charAt(boundary);
        return Character.isWhitespace(lastPrev) || Character.isWhitespace(nextCurr)
                || isCjkOrKana(curr.codePointAt(boundary))
                || isCjkOrKana(Character.codePointBefore(prev, prev.length()))
                || !Character.isLetterOrDigit(nextCurr);
    }

    private static int[] matchLineSuffixToPrefix(String prev, String curr) {
        int pos = prev.indexOf('\n');
        while (pos >= 0 && pos + 1 < prev.length()) {
            int suffixStart = pos + 1;
            int suffixLen = prev.length() - suffixStart;
            if (suffixLen > 0 && curr.length() >= suffixLen
                    && curr.regionMatches(0, prev, suffixStart, suffixLen)
                    && (curr.length() == suffixLen || curr.charAt(suffixLen) == '\n')
                    && MappedTranscript.boundary(prev, suffixStart)
                    && MappedTranscript.boundary(curr, suffixLen)) {
                return new int[]{suffixStart, suffixLen};
            }
            pos = prev.indexOf('\n', suffixStart);
        }
        return null;
    }

    private static int[] matchWordBoundaryOverlap(String prev, String curr) {
        for (int idx = 1; idx < prev.length(); idx++) {
            char before = prev.charAt(idx - 1);
            if (!Character.isWhitespace(before) || Character.isWhitespace(prev.charAt(idx))) {
                continue;
            }
            int len = prev.length() - idx;
            if (len > curr.length() || !curr.regionMatches(0, prev, idx, len)) {
                continue;
            }
            boolean endsAtBoundary = curr.length() == len || Character.isWhitespace(curr.charAt(len));
            if (!endsAtBoundary || !MappedTranscript.boundary(prev, idx) || !MappedTranscript.boundary(curr, len)) {
                continue;
            }
            if (qualifiesOverlapPhrase(curr, len)) {
                return new int[]{idx, len};
            }
        }
        return null;
    }

    private static boolean qualifiesOverlapPhrase(String text, int length) {
        int words = 0;
        int cjkCount = 0;
        boolean inWord = false;
        for (int i = 0; i < length;) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                inWord = false;
            } else {
                if (!inWord) {
                    words++;
                    inWord = true;
                }
                if (isCjkOrKana(cp)) {
                    cjkCount++;
                }
            }
            i += Character.charCount(cp);
        }
        return words >= 2 || cjkCount >= 4;
    }

    private static boolean isCjkOrKana(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x30FF);
    }

    private static boolean applyTtmlEchoMatch(List<Unit> flowUnits, List<Unit> prevUnitRefs,
                                              int cueIndex, String currText,
                                              int matchStartInPrev, int matchLen) {
        int offset = 0;
        int startRefIdx = -1;
        for (int i = 0; i < prevUnitRefs.size(); i++) {
            Unit u = prevUnitRefs.get(i);
            int end = offset + u.text.length();
            if (matchStartInPrev < end) {
                if (matchStartInPrev > offset) {
                    int cut = matchStartInPrev - offset;
                    if (!MappedTranscript.boundary(u.text, cut)) return false;
                    splitUnitInPlace(flowUnits, prevUnitRefs, i, u, cut);
                    startRefIdx = i + 1;
                } else {
                    startRefIdx = i;
                }
                break;
            }
            offset = end;
        }
        if (startRefIdx < 0) return false;
        int currPos = 0;
        for (int i = startRefIdx; i < prevUnitRefs.size(); i++) {
            Unit u = prevUnitRefs.get(i);
            if (currPos + u.text.length() > matchLen
                    || !currText.regionMatches(currPos, u.text, 0, u.text.length())) {
                return false;
            }
            u.add(cueIndex, currPos);
            currPos += u.text.length();
        }
        return currPos == matchLen;
    }

    private static void splitUnitInPlace(List<Unit> flowUnits, List<Unit> prevUnitRefs,
                                         int refIdx, Unit target, int cut) {
        Unit left = new Unit(target.flowIndex, target.text.substring(0, cut));
        Unit right = new Unit(target.flowIndex, target.text.substring(cut));
        for (Occurrence occ : target.occurrences) {
            left.add(occ.cue, occ.start);
            right.add(occ.cue, occ.start + cut);
        }
        int flowIdx = flowUnits.lastIndexOf(target);
        if (flowIdx < 0) throw new Unresolved();
        flowUnits.set(flowIdx, left);
        flowUnits.add(flowIdx + 1, right);
        prevUnitRefs.set(refIdx, left);
        prevUnitRefs.add(refIdx + 1, right);
    }

    private static List<Unit> collectTailUnitRefs(List<Unit> prevUnitRefs, int matchStartInPrev) {
        int offset = 0;
        List<Unit> tail = new ArrayList<>();
        for (Unit u : prevUnitRefs) {
            if (offset >= matchStartInPrev) {
                tail.add(u);
            }
            offset += u.text.length();
        }
        return tail;
    }

    private static Analysis preserve(RawTimeline raw, MappedTranscript transcript,
                                     SemanticGroupPlanner.Limits limits, Reason reason) {
        try {
            return new Analysis(raw, transcript, SemanticGroupPlanner.preserve(transcript, limits), false, reason);
        } catch (IllegalArgumentException exceeded) {
            return new Analysis(raw, transcript, null, false, Reason.PRESERVED_BUDGET);
        }
    }

    /**
     * Two or more non-empty lines where the carried prefix has no fresh internal word clocks
     * and the runs form a continuous VTT window or single CUE_INTERVAL covering the cue.
     */
    private static int rollingSplit(RawTimeline.Cue cue) {
        String text = cue.mappingText;
        int firstLine = text.indexOf('\n');
        if (firstLine <= 0 || firstLine == text.length() - 1) return -1;
        int split = text.lastIndexOf('\n');
        if (split <= 0 || split == text.length() - 1) return -1;
        if (cue.runs.size() == 1) {
            RawTimeline.TextRun single = cue.runs.get(0);
            if (single.timingKind == RawTimeline.TimingKind.CUE_INTERVAL
                    && cue.endUs - cue.startUs > MAX_HOLD_US
                    && !text.substring(split + 1).trim().isEmpty()
                    && single.activeStartUs == cue.startUs && single.activeEndUs == cue.endUs
                    && single.timingStartUs == cue.startUs && single.timingEndUs == cue.endUs) {
                return split;
            }
        }
        boolean internal = false;
        long clock = cue.startUs;
        for (RawTimeline.TextRun run : cue.runs) {
            if (run.timingKind != RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW ||
                    run.activeStartUs != cue.startUs || run.activeEndUs != cue.endUs ||
                    run.timingStartUs != clock) return -1;
            internal |= run.textStart >= split + 1 && run.textStart < text.length() &&
                    run.timingStartUs > cue.startUs && run.timingStartUs < cue.endUs;
            clock = run.timingEndUs;
        }
        return internal && clock <= cue.endUs ? split : -1;
    }

    private static boolean shortHold(RawTimeline.Cue cue, boolean allowMultiLine) {
        long duration = cue.endUs - cue.startUs;
        String text = holdText(cue);
        if (duration <= 0 || duration > MAX_HOLD_US || text.isEmpty() ||
                (!allowMultiLine && text.indexOf('\n') >= 0)) return false;
        // A hold is one unannotated display interval, not another paint-on/revision window.
        if (cue.runs.size() != 1) return false;
        RawTimeline.TextRun run = cue.runs.get(0);
        return run.timingKind == RawTimeline.TimingKind.CUE_INTERVAL &&
                run.activeStartUs == cue.startUs && run.activeEndUs == cue.endUs &&
                run.timingStartUs == cue.startUs && run.timingEndUs == cue.endUs;
    }

    private static boolean spokenOneLine(RawTimeline.Cue cue) {
        long duration = cue.endUs - cue.startUs;
        String text = holdText(cue);
        if (duration <= MAX_HOLD_US || text.isEmpty() || text.indexOf('\n') >= 0) return false;
        if (cue.runs.size() == 1 && cue.runs.get(0).timingKind == RawTimeline.TimingKind.CUE_INTERVAL) {
            RawTimeline.TextRun run = cue.runs.get(0);
            return run.activeStartUs == cue.startUs && run.activeEndUs == cue.endUs
                    && run.timingStartUs == cue.startUs && run.timingEndUs == cue.endUs;
        }
        long clock = cue.startUs;
        for (RawTimeline.TextRun run : cue.runs) {
            if (run.timingKind != RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW
                    || run.activeStartUs != cue.startUs || run.activeEndUs != cue.endUs
                    || run.timingStartUs != clock) return false;
            clock = run.timingEndUs;
        }
        return !cue.runs.isEmpty() && clock <= cue.endUs;
    }

    private static boolean hasInlineVttTimestamps(RawTimeline.Cue cue) {
        for (RawTimeline.TextRun run : cue.runs) {
            if (run.timingKind == RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW) return true;
        }
        return false;
    }

    private static boolean spokenLeadForHold(List<RawTimeline.Cue> cues, int leadIndex,
                                           RawTimeline.Cue hold, RawTimeline.Cue rich, int split) {
        RawTimeline.Cue lead = cues.get(leadIndex);
        if (!spokenOneLine(lead) || !shortHold(hold, false)
                || !touching(lead, hold) || !touching(hold, rich) || !plainOldRow(rich, split)) {
            return false;
        }
        String leadText = holdText(lead);
        if (!leadText.equals(holdText(hold)) || !leadText.equals(rich.mappingText.substring(0, split))) {
            return false;
        }
        return (hasInlineVttTimestamps(lead) || (lead.endUs - lead.startUs) >= MIN_SPOKEN_LEAD_US)
                && (leadIndex == 0 || !cueTailMatches(cues.get(leadIndex - 1), leadText));
    }

    private static boolean spokenLeadDirect(List<RawTimeline.Cue> cues, int leadIndex,
                                            RawTimeline.Cue rich, int split) {
        RawTimeline.Cue lead = cues.get(leadIndex);
        if (!spokenOneLine(lead) || !touching(lead, rich) || !plainOldRow(rich, split)) return false;
        String leadText = holdText(lead);
        if (!leadText.equals(rich.mappingText.substring(0, split))) return false;
        if (hasInlineVttTimestamps(lead)) return true;
        long duration = lead.endUs - lead.startUs;
        return duration >= MIN_SPOKEN_LEAD_US
                && (leadIndex == 0 || !cueTailMatches(cues.get(leadIndex - 1), leadText));
    }

    private static boolean cueTailMatches(RawTimeline.Cue previous, String text) {
        String prev = previous.mappingText;
        int nl = prev.lastIndexOf('\n');
        String tail = nl >= 0 && nl + 1 < prev.length() ? prev.substring(nl + 1) : holdText(previous);
        return tail.equals(text);
    }

    private static String holdText(RawTimeline.Cue cue) {
        // This producer keeps a blank second display row in its ten-millisecond holds.
        // Match that exact padding only; component() retains both characters and their clocks.
        String text = cue.mappingText;
        return text.endsWith("\n ") ? text.substring(0, text.length() - 2) : text;
    }

    private static boolean touching(RawTimeline.Cue a, RawTimeline.Cue b) {
        return a.endUs == b.startUs && a.webvttSettings.equals(b.webvttSettings);
    }

    private static boolean head(RawTimeline.Cue hold, RawTimeline.Cue rich, int split) {
        return shortHold(hold, false) && touching(hold, rich) && plainOldRow(rich, split) &&
                holdText(hold).equals(rich.mappingText.substring(0, split));
    }

    private static boolean plainOldRow(RawTimeline.Cue cue, int split) {
        // A carried-looking row with new internal times may be a new utterance, not a display echo.
        for (RawTimeline.TextRun run : cue.runs) if (run.textStart > 0 && run.textStart < split) return false;
        return true;
    }

    private static boolean tail(RawTimeline.Cue rich, int split, RawTimeline.Cue hold) {
        return shortHold(hold, false) && touching(rich, hold) &&
                holdText(hold).equals(rich.mappingText.substring(split + 1));
    }

    private static boolean link(List<RawTimeline.Cue> cues, int[] split, int index) {
        if (index < 0 || index + 2 >= cues.size() || split[index] < 0 || split[index + 2] < 0) return false;
        RawTimeline.Cue curr = cues.get(index);
        RawTimeline.Cue mid = cues.get(index + 1);
        RawTimeline.Cue next = cues.get(index + 2);
        if (tail(curr, split[index], mid) && head(mid, next, split[index + 2])) {
            return true;
        }
        // 3-line rolling window: L1\nL2\nL3 -> [hold L2\nL3] -> L2\nL3\nL4
        String midText = holdText(mid);
        if (midText.indexOf('\n') > 0 && shortHold(mid, true) && touching(curr, mid)
                && touching(mid, next) && plainOldRow(next, split[index + 2])) {
            String currText = curr.mappingText;
            String nextPrefix = next.mappingText.substring(0, split[index + 2]);
            return midText.equals(nextPrefix)
                    && currText.length() > midText.length()
                    && currText.endsWith(midText)
                    && currText.charAt(currText.length() - midText.length() - 1) == '\n';
        }
        return false;
    }

    private static List<MappedTranscript.Atom> component(RawTimeline raw, int start, int end,
                                                        boolean headLead, boolean headHold, boolean tail,
                                                        int[] split, int atomIndex, int flow,
                                                        int maxAtoms, int maxReferences) {
        List<Unit> units = new ArrayList<>();
        RawTimeline.Cue first = raw.getCues().get(start);
        String firstTop = first.mappingText.substring(0, split[start]);
        Unit current = new Unit(flow, firstTop);
        int leadCue = headLead ? (headHold ? start - 2 : start - 1) : -1;
        if (headLead) current.add(leadCue, 0);
        if (headHold) current.add(start - 1, 0);
        current.add(start, 0);
        units.add(current);
        for (int index = start; index <= end; index += 2) {
            if (index > start) {
                String prefix = raw.getCues().get(index).mappingText.substring(0, split[index]);
                if (!addSuffixOccurrences(units, prefix, index)) {
                    throw new Unresolved();
                }
            }
            Unit separator = new Unit(flow, "\n");
            int leadSepCue = index == start && headLead ? leadCue : -1;
            int precedingHold = (index > start || headHold) ? index - 1 : -1;
            RawTimeline.Cue leadHoldCue = leadSepCue >= 0 ? raw.getCues().get(leadSepCue) : null;
            RawTimeline.Cue holdCue = precedingHold >= 0 ? raw.getCues().get(precedingHold) : null;
            boolean leadPadding = leadHoldCue != null && leadHoldCue.mappingText.endsWith("\n ");
            boolean holdPadding = holdCue != null && holdCue.mappingText.endsWith("\n ");
            if (leadPadding) separator.add(leadSepCue, leadHoldCue.mappingText.length() - 2);
            separator.add(index, split[index]);
            if (holdPadding) {
                // Keep exact original occurrence order: primary (earliest) before echo.
                separator.add(precedingHold, holdCue.mappingText.length() - 2);
            }
            // Note: maintain chronological cue order on separator when headHold precedes index without headLead.
            if (!leadPadding && holdPadding && precedingHold < index) {
                separator.occurrences.clear();
                separator.add(precedingHold, holdCue.mappingText.length() - 2);
                separator.add(index, split[index]);
            }
            units.add(separator);
            if (leadPadding || holdPadding) {
                Unit space = new Unit(flow, " ");
                if (leadPadding) space.add(leadSepCue, leadHoldCue.mappingText.length() - 1);
                if (holdPadding) space.add(precedingHold, holdCue.mappingText.length() - 1);
                units.add(space);
            }
            current = new Unit(flow, raw.getCues().get(index).mappingText.substring(split[index] + 1));
            current.add(index, split[index] + 1);
            units.add(current);
            if (index < end || tail) {
                String midHoldText = holdText(raw.getCues().get(index + 1));
                if (!addSuffixOccurrences(units, midHoldText, index + 1)) {
                    throw new Unresolved();
                }
            }
        }
        if (tail && raw.getCues().get(end + 1).mappingText.endsWith("\n ")) {
            RawTimeline.Cue holdCue = raw.getCues().get(end + 1);
            Unit padding = new Unit(flow, "\n ");
            padding.add(end + 1, holdCue.mappingText.length() - 2);
            units.add(padding);
        }
        return emitAtoms(raw, units, atomIndex, maxAtoms, maxReferences);
    }

    private static boolean addSuffixOccurrences(List<Unit> units, String expectedSuffix, int cueIndex) {
        int remaining = expectedSuffix.length();
        int firstUnit = units.size();
        while (remaining > 0 && firstUnit > 0) {
            firstUnit--;
            remaining -= units.get(firstUnit).text.length();
        }
        if (remaining < 0) {
            Unit target = units.get(firstUnit);
            int cut = -remaining;
            if (cut <= 0 || cut >= target.text.length() || !MappedTranscript.boundary(target.text, cut)) {
                return false;
            }
            Unit left = new Unit(target.flowIndex, target.text.substring(0, cut));
            Unit right = new Unit(target.flowIndex, target.text.substring(cut));
            for (Occurrence occ : target.occurrences) {
                left.add(occ.cue, occ.start);
                right.add(occ.cue, occ.start + cut);
            }
            units.set(firstUnit, left);
            units.add(firstUnit + 1, right);
            firstUnit++;
            remaining = 0;
        }
        if (remaining != 0) return false;
        int pos = 0;
        for (int i = firstUnit; i < units.size(); i++) {
            Unit u = units.get(i);
            if (!expectedSuffix.regionMatches(pos, u.text, 0, u.text.length())) return false;
            u.add(cueIndex, pos);
            pos += u.text.length();
        }
        return pos == expectedSuffix.length();
    }

    private static List<MappedTranscript.Atom> emitAtoms(RawTimeline raw, List<Unit> units,
                                                         int atomIndex, int maxAtoms, int maxReferences) {
        List<MappedTranscript.Atom> atoms = new ArrayList<>();
        int references = 0;
        for (Unit unit : units) {
            TreeSet<Integer> cuts = new TreeSet<>();
            cuts.add(0);
            cuts.add(unit.text.length());
            for (Occurrence occurrence : unit.occurrences) {
                RawTimeline.Cue cue = raw.getCues().get(occurrence.cue);
                int limit = occurrence.start + unit.text.length();
                if (limit > cue.mappingText.length() ||
                        !cue.mappingText.regionMatches(occurrence.start, unit.text, 0, unit.text.length())) {
                    throw new Unresolved();
                }
                for (RawTimeline.TextRun run : cue.runs) {
                    if (run.textEnd <= occurrence.start) continue;
                    if (run.textStart >= limit) break;
                    cuts.add(Math.max(run.textStart, occurrence.start) - occurrence.start);
                    cuts.add(Math.min(run.textEnd, limit) - occurrence.start);
                    if (cuts.size() - 1 > maxAtoms - atoms.size()) throw new Budget();
                }
            }
            // Each occurrence is walked monotonically; there is no substring search or fuzzy alias.
            int[] runs = new int[unit.occurrences.size()];
            Integer previous = null;
            for (int cut : cuts) {
                if (previous != null) {
                    if (atoms.size() >= maxAtoms || unit.occurrences.size() > maxReferences - references) {
                        throw new Budget();
                    }
                    if (!MappedTranscript.boundary(unit.text, previous) || !MappedTranscript.boundary(unit.text, cut)) {
                        throw new Unresolved();
                    }
                    List<MappedTranscript.Origin> origins = new ArrayList<>();
                    for (int o = 0; o < unit.occurrences.size(); o++) {
                        Occurrence occurrence = unit.occurrences.get(o);
                        RawTimeline.Cue cue = raw.getCues().get(occurrence.cue);
                        int from = occurrence.start + previous, to = occurrence.start + cut;
                        while (runs[o] < cue.runs.size() && cue.runs.get(runs[o]).textEnd <= from) runs[o]++;
                        if (runs[o] == cue.runs.size() || cue.runs.get(runs[o]).textStart > from ||
                                cue.runs.get(runs[o]).textEnd < to) throw new Unresolved();
                        origins.add(new MappedTranscript.Origin(occurrence.cue, cue, runs[o], from, to, o > 0));
                    }
                    atoms.add(new MappedTranscript.Atom(atomIndex + atoms.size(), unit.flowIndex,
                            unit.text.substring(previous, cut), origins));
                    references += origins.size();
                }
                previous = cut;
            }
        }
        return atoms;
    }

    private static final class Occurrence {
        final int cue;
        final int start;
        Occurrence(int cue, int start) { this.cue = cue; this.start = start; }
    }

    private static final class Unit {
        final int flowIndex;
        final String text;
        final List<Occurrence> occurrences = new ArrayList<>();
        Unit(int flowIndex, String text) { this.flowIndex = flowIndex; this.text = text; }
        void add(int cue, int start) { occurrences.add(new Occurrence(cue, start)); }
    }

    private static final class Builder {
        final RawTimeline raw;
        final int maxAtoms;
        final int maxReferences;
        final List<MappedTranscript.Atom> atoms = new ArrayList<>();
        int references;
        Builder(RawTimeline raw, int maxAtoms, int maxReferences) {
            this.raw = raw;
            this.maxAtoms = maxAtoms;
            this.maxReferences = maxReferences;
        }
        void preserve(int index) {
            RawTimeline.Cue cue = raw.getCues().get(index);
            for (int run = 0; run < cue.runs.size(); run++) {
                if (atoms.size() >= maxAtoms || references >= maxReferences) throw new Budget();
                RawTimeline.TextRun source = cue.runs.get(run);
                atoms.add(new MappedTranscript.Atom(atoms.size(), index,
                        cue.mappingText.substring(source.textStart, source.textEnd),
                        Collections.singletonList(new MappedTranscript.Origin(index, cue, run))));
                references++;
            }
        }
        void append(List<MappedTranscript.Atom> component) {
            for (MappedTranscript.Atom atom : component) {
                if (atoms.size() >= maxAtoms || atom.origins.size() > maxReferences - references) throw new Budget();
                atoms.add(atom);
                references += atom.origins.size();
            }
        }
    }

    private static final class Budget extends RuntimeException { }
    private static final class Unresolved extends RuntimeException { }
}
