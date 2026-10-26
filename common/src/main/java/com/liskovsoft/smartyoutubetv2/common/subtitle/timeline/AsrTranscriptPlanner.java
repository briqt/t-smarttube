package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Bounded, source-only recognition of one touching WebVTT paint-on profile. No fetching,
 * display policy, acoustic speaker inference, or translation/cache identity is supplied here.
 * The caller must bind {@code source} uniquely to this exact Raw document before calling.
 */
public final class AsrTranscriptPlanner {
    private static final int MAX_REFERENCES = 65536;
    private static final int CANDIDATE_CODE_POINTS = 120;
    private static final long MAX_HOLD_US = 20000;
    // Inspect original markup, not decoded mappingText: &lt;v is ordinary text.
    private static final Pattern DECLARATION = Pattern.compile("<(?:/?(?:v|lang)(?:[ \\t>])|/?c\\.)");

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
        String language = raw.getMetadata().get("Language");
        if (language != null && !language.equalsIgnoreCase(source.getSourceLanguage())) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_LANGUAGE);
        }
        String document;
        try {
            document = RawTimeline.decodeUtf8(bytes, limits);
        } catch (IllegalArgumentException unsupported) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_FORMAT);
        }
        int first = document.startsWith("\ufeff") ? 1 : 0;
        if (!document.startsWith("WEBVTT", first) || document.length() <= first + 6 ||
                " \t\r\n".indexOf(document.charAt(first + 6)) < 0) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_FORMAT);
        }
        if (DECLARATION.matcher(document).find()) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_DECLARATION);
        }

        List<RawTimeline.Cue> cues = raw.getCues();
        int[] newline = new int[cues.size()];
        for (int i = 0; i < newline.length; i++) newline[i] = rich(cues.get(i));
        Builder output = new Builder(raw, limits.textRuns, Math.min(MAX_REFERENCES, groups.references));
        boolean normalized = false;
        try {
            for (int cue = 0; cue < cues.size();) {
                int richStart = newline[cue] >= 0 ? cue : cue + 1;
                boolean head = richStart == cue + 1 && richStart < cues.size() &&
                        newline[richStart] >= 0 && head(cues.get(cue), cues.get(richStart), newline[richStart]);
                if ((!head && richStart != cue) || !link(cues, newline, richStart)) {
                    output.preserve(cue++);
                    continue;
                }
                int richEnd = richStart + 2;
                while (link(cues, newline, richEnd)) richEnd += 2;
                int end = richEnd + 1;
                boolean tail = end < cues.size() && tail(cues.get(richEnd), newline[richEnd], cues.get(end));
                if (tail) end++;
                // Build locally: an exact-range failure cannot publish a prefix of a component.
                List<MappedTranscript.Atom> component;
                try {
                    component = component(raw, richStart, richEnd, head, tail, newline,
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
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.candidates(transcript, CANDIDATE_CODE_POINTS, groups);
            return new Analysis(raw, transcript, plan, true, Reason.PAINT_ON);
        } catch (Budget exceeded) {
            return preserve(raw, preserved, groups, Reason.PRESERVED_BUDGET);
        } catch (IllegalArgumentException invalidMappingOrPlan) {
            // Constructors/planner reject the entire result, with no source-bearing diagnostics.
            return preserve(raw, preserved, groups, Reason.PRESERVED_BUDGET);
        }
    }

    private static Analysis preserve(RawTimeline raw, MappedTranscript transcript,
                                     SemanticGroupPlanner.Limits limits, Reason reason) {
        try {
            return new Analysis(raw, transcript, SemanticGroupPlanner.preserve(transcript, limits), false, reason);
        } catch (IllegalArgumentException exceeded) {
            return new Analysis(raw, transcript, null, false, Reason.PRESERVED_BUDGET);
        }
    }

    /** Exactly two nonempty lines, with a real timestamp boundary strictly inside the new line. */
    private static int rich(RawTimeline.Cue cue) {
        String text = cue.mappingText;
        int line = text.indexOf('\n');
        if (line <= 0 || line == text.length() - 1 || text.indexOf('\n', line + 1) >= 0) return -1;
        boolean internal = false;
        long clock = cue.startUs;
        for (RawTimeline.TextRun run : cue.runs) {
            if (run.timingKind != RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW ||
                    run.activeStartUs != cue.startUs || run.activeEndUs != cue.endUs ||
                    run.timingStartUs != clock) return -1;
            internal |= run.textStart > line + 1 && run.textStart < text.length() &&
                    run.timingStartUs > cue.startUs && run.timingStartUs < cue.endUs;
            clock = run.timingEndUs;
        }
        return internal && clock == cue.endUs ? line : -1;
    }

    private static boolean shortHold(RawTimeline.Cue cue) {
        long duration = cue.endUs - cue.startUs;
        String text = holdText(cue);
        if (duration <= 0 || duration > MAX_HOLD_US || text.isEmpty() ||
                text.indexOf('\n') >= 0) return false;
        // A hold is one unannotated display interval, not another paint-on/revision window.
        if (cue.runs.size() != 1) return false;
        RawTimeline.TextRun run = cue.runs.get(0);
        return run.timingKind == RawTimeline.TimingKind.CUE_INTERVAL &&
                run.activeStartUs == cue.startUs && run.activeEndUs == cue.endUs &&
                run.timingStartUs == cue.startUs && run.timingEndUs == cue.endUs;
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

    private static boolean head(RawTimeline.Cue hold, RawTimeline.Cue rich, int line) {
        return shortHold(hold) && touching(hold, rich) && plainOldRow(rich, line) &&
                holdText(hold).equals(rich.mappingText.substring(0, line));
    }

    private static boolean plainOldRow(RawTimeline.Cue cue, int line) {
        // A carried-looking row with new internal times may be a new utterance, not a display echo.
        for (RawTimeline.TextRun run : cue.runs) if (run.textStart > 0 && run.textStart < line) return false;
        return true;
    }

    private static boolean tail(RawTimeline.Cue rich, int line, RawTimeline.Cue hold) {
        return shortHold(hold) && touching(rich, hold) &&
                holdText(hold).equals(rich.mappingText.substring(line + 1));
    }

    private static boolean link(List<RawTimeline.Cue> cues, int[] newline, int index) {
        if (index < 0 || index + 2 >= cues.size() || newline[index] < 0 || newline[index + 2] < 0) return false;
        return tail(cues.get(index), newline[index], cues.get(index + 1)) &&
                head(cues.get(index + 1), cues.get(index + 2), newline[index + 2]);
    }

    private static List<MappedTranscript.Atom> component(RawTimeline raw, int start, int end,
                                                        boolean head, boolean tail, int[] newline,
                                                        int atomIndex, int flow, int maxAtoms, int maxReferences) {
        List<Unit> units = new ArrayList<>();
        RawTimeline.Cue first = raw.getCues().get(start);
        Unit current = new Unit(first.mappingText.substring(0, newline[start]));
        if (head) current.add(start - 1, 0);
        current.add(start, 0);
        units.add(current);
        for (int index = start; index <= end; index += 2) {
            if (index > start) current.add(index, 0);
            Unit separator = new Unit("\n");
            separator.add(index, newline[index]);
            int precedingHold = index - 1;
            RawTimeline.Cue holdCue = precedingHold >= 0 ? raw.getCues().get(precedingHold) : null;
            boolean padding = (index > start || head) && holdCue.mappingText.endsWith("\n ");
            if (padding) separator.add(precedingHold, holdCue.mappingText.length() - 2);
            units.add(separator);
            if (padding) {
                Unit space = new Unit(" ");
                space.add(precedingHold, holdCue.mappingText.length() - 1);
                units.add(space);
            }
            current = new Unit(raw.getCues().get(index).mappingText.substring(newline[index] + 1));
            current.add(index, newline[index] + 1);
            units.add(current);
            if (index < end || tail) current.add(index + 1, 0);
        }
        if (tail && raw.getCues().get(end + 1).mappingText.endsWith("\n ")) {
            RawTimeline.Cue holdCue = raw.getCues().get(end + 1);
            Unit padding = new Unit("\n ");
            padding.add(end + 1, holdCue.mappingText.length() - 2);
            units.add(padding);
        }
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
                    atoms.add(new MappedTranscript.Atom(atomIndex + atoms.size(), flow,
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
        final String text;
        final List<Occurrence> occurrences = new ArrayList<>();
        Unit(String text) { this.text = text; }
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
