package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Bounded production parser for TTML captions ({@code application/ttml+xml}), including Innertube
 * {@code <head>} styles/regions and {@code <p>} / {@code <span>} / {@code <br/>} word-level timing.
 * Bitmap captions, sequential time containers, and unsafe XML constructs fail closed so native
 * presentation or fallback handles them safely.
 */
public final class TtmlTimelineParser {
    private static final int MAX_DEPTH = 32;
    private static final String TT = "http://www.w3.org/ns/ttml";
    private static final String TTM = TT + "#metadata";
    private static final String TTP = TT + "#parameter";
    private static final String XML = "http://www.w3.org/XML/1998/namespace";
    private static final Pattern CLOCK_TIME =
            Pattern.compile("^([0-9]+):([0-5][0-9]):([0-5][0-9])(?:(\\.[0-9]+)|:([0-9][0-9]+)(?:\\.([0-9]+))?)?$");
    private static final Pattern OFFSET_TIME = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(h|m|s|ms|f|t)$");
    private static final Pattern ENCODING = Pattern.compile("\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\n]+");

    private TtmlTimelineParser() {
    }

    public static RawTimeline parseForNativePlayback(byte[] source, long effectiveOffsetUs, RawTimeline.Limits limits) {
        if (source == null || limits == null || source.length == 0 || source.length > limits.documentBytes) {
            throw RawTimeline.invalid();
        }
        byte[] snapshot = source.clone();
        String xml = RawTimeline.decodeUtf8(snapshot, limits);
        if (xml.indexOf('\0') >= 0) {
            throw RawTimeline.invalid();
        }
        if (xml.startsWith("\uFEFF")) {
            xml = xml.substring(1);
        }
        int declarationEnd = xml.startsWith("<?xml") ? xml.indexOf("?>") : -1;
        if (declarationEnd >= 0) {
            Matcher encoding = ENCODING.matcher(xml.substring(0, declarationEnd));
            if (encoding.find() && !"UTF-8".equalsIgnoreCase(encoding.group(2))) {
                throw RawTimeline.invalid();
            }
        }
        String lower = xml.toLowerCase(Locale.US);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw RawTimeline.invalid();
        }
        int maxNodes = Math.max(16384, limits.cues * 4 + limits.textRuns * 2 + 1024);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            boolean expatFactory = factory.getClass().getName().contains("harmony.xml.");
            if (!expatFactory) {
                factory.setXIncludeAware(false);
            }
            XMLReader reader = factory.newSAXParser().getXMLReader();
            boolean expatReader = expatFactory || reader.getClass().getName().endsWith(".ExpatReader");
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            if (!expatReader) {
                configureStrictXmlReader(reader);
            }
            Handler handler = new Handler(effectiveOffsetUs, limits, maxNodes);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return new RawTimeline(snapshot, handler.cues, limits, handler.metadata);
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException ignored) {
            throw RawTimeline.invalid();
        }
    }

    private static void configureStrictXmlReader(XMLReader reader)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        reader.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
    }

    private static final class TimingProfile {
        final float effectiveFrameRate;
        final int subFrameRate;
        final int tickRate;

        TimingProfile(float effectiveFrameRate, int subFrameRate, int tickRate) {
            this.effectiveFrameRate = effectiveFrameRate;
            this.subFrameRate = subFrameRate;
            this.tickRate = tickRate;
        }
    }

    private static final class Frame {
        final String name;
        final boolean inHead;
        final boolean ignoredSubtree;
        final boolean inParagraph;
        final boolean preserveSpace;
        final long startUs;
        final Long endUs;
        final boolean hasOwnEnd;
        final boolean inlineTiming;
        final String sourceId;
        boolean subTimingCorrupted;

        Frame(String name, Frame parent, Attributes attrs, TimingProfile timing) {
            this.name = name;
            inHead = (parent != null && parent.inHead) || "head".equals(name);
            ignoredSubtree = (parent != null && parent.ignoredSubtree) || inHead
                    || "metadata".equals(name) || "set".equals(name);
            inParagraph = parent != null && (parent.inParagraph || "p".equals(parent.name));
            String space = attrValue(attrs, XML, "space");
            if (space == null) {
                preserveSpace = parent != null && parent.preserveSpace;
            } else if ("preserve".equals(space)) {
                preserveSpace = true;
            } else if ("default".equals(space)) {
                preserveSpace = false;
            } else {
                throw RawTimeline.invalid();
            }
            sourceId = attrValue(attrs, XML, "id");

            if (ignoredSubtree) {
                startUs = 0;
                endUs = null;
                hasOwnEnd = false;
                inlineTiming = false;
                return;
            }

            long syncbase = parent == null ? 0L : parent.startUs;
            String begin = attrs.getValue("", "begin");
            String end = attrs.getValue("", "end");
            String dur = attrs.getValue("", "dur");
            boolean childInsideP = inParagraph && ("span".equals(name) || "br".equals(name));
            inlineTiming = (parent != null && parent.inlineTiming)
                    || (childInsideP && (begin != null || end != null || dur != null));

            long parsedStart = syncbase;
            Long parsedOwnEnd = null;
            boolean timingMalformed = false;
            try {
                if (begin != null) {
                    parsedStart = RawTimeline.add(syncbase, timeUs(begin, timing));
                }
                if (end != null) {
                    parsedOwnEnd = RawTimeline.add(syncbase, timeUs(end, timing));
                }
                if (dur != null) {
                    long durEnd = RawTimeline.add(parsedStart, timeUs(dur, timing));
                    parsedOwnEnd = min(parsedOwnEnd, durEnd);
                }
            } catch (IllegalArgumentException badChildTime) {
                if (!childInsideP) {
                    throw badChildTime;
                }
                timingMalformed = true;
            }

            if (!timingMalformed && parsedOwnEnd != null && parsedOwnEnd <= parsedStart) {
                if (!childInsideP) {
                    // Empty/reversed top-level paragraph produces no subtitle; span degrades safely.
                    parsedOwnEnd = parsedStart;
                } else {
                    timingMalformed = true;
                }
            }
            if (timingMalformed) {
                subTimingCorrupted = true;
            }
            startUs = parsedStart;
            hasOwnEnd = parsedOwnEnd != null;
            endUs = min(parsedOwnEnd, parent == null ? null : parent.endUs);
        }
    }

    private static final class Part {
        final Frame owner;
        final StringBuilder value = new StringBuilder();
        final boolean lineBreak;

        Part(Frame owner, boolean lineBreak) {
            this.owner = owner;
            this.lineBreak = lineBreak;
        }
    }

    private static final class Segment {
        final Frame owner;
        final String text;
        final boolean lineBreak;

        Segment(Frame owner, String text, boolean lineBreak) {
            this.owner = owner;
            this.text = text;
            this.lineBreak = lineBreak;
        }
    }

    private static final class Handler extends DefaultHandler2 {
        final long offsetUs;
        final RawTimeline.Limits limits;
        final int maxNodes;
        final List<RawTimeline.Cue> cues = new ArrayList<>();
        final Map<String, String> metadata = new LinkedHashMap<>();
        final Deque<Frame> stack = new ArrayDeque<>();
        TimingProfile timing = new TimingProfile(30f, 1, 1);
        String namespace;
        boolean bodySeen;
        boolean headSeen;
        int nodes;
        int totalRuns;
        Frame currentParagraph;
        List<Part> parts;
        Part textPart;

        Handler(long offsetUs, RawTimeline.Limits limits, int maxNodes) {
            this.offsetUs = offsetUs;
            this.limits = limits;
            this.maxNodes = maxNodes;
        }

        private void countNode() {
            if (++nodes > maxNodes) {
                throw RawTimeline.invalid();
            }
        }

        private Part appendPart(Frame owner, boolean lineBreak) {
            Part part = new Part(owner, lineBreak);
            parts.add(part);
            return part;
        }

        @Override
        public void startElement(String uri, String name, String qualified, Attributes attrs) {
            countNode();
            if (stack.size() >= MAX_DEPTH) {
                throw RawTimeline.invalid();
            }
            if ("image".equals(name)) {
                throw RawTimeline.invalid();
            }
            inspectCommonAttributes(attrs);

            Frame parent = stack.peek();
            String parentName = parent == null ? "" : parent.name;
            if (parent == null) {
                if (namespace != null || !"tt".equals(name) || !(uri.isEmpty() || TT.equals(uri))) {
                    throw RawTimeline.invalid();
                }
                namespace = uri;
                parseRootAttributes(attrs);
            } else if (parent.inHead || parent.ignoredSubtree) {
                // Safely traverse head styling/layout/region/metadata without rejecting styling tags.
            } else if (!isAllowedBodyChild(uri, name, parentName)) {
                throw RawTimeline.invalid();
            }

            if ("head".equals(name) && "tt".equals(parentName)) {
                if (headSeen || bodySeen) {
                    throw RawTimeline.invalid();
                }
                headSeen = true;
            } else if ("body".equals(name) && "tt".equals(parentName)) {
                if (bodySeen) {
                    throw RawTimeline.invalid();
                }
                bodySeen = true;
            }

            Frame frame = new Frame(name, parent, attrs, timing);
            if ("p".equals(name) && !frame.ignoredSubtree) {
                if (frame.endUs == null) {
                    throw RawTimeline.invalid();
                }
                currentParagraph = frame;
                parts = new ArrayList<>();
            } else if (currentParagraph != null && frame.subTimingCorrupted) {
                currentParagraph.subTimingCorrupted = true;
            }
            stack.push(frame);
            textPart = null;
            if ("br".equals(name) && currentParagraph != null && !frame.ignoredSubtree) {
                appendPart(frame, true).value.append('\n');
            }
        }

        private boolean isAllowedBodyChild(String uri, String name, String parentName) {
            if ("head".equals(name) || "body".equals(name)) {
                return "tt".equals(parentName) && namespace.equals(uri);
            }
            if ("metadata".equals(name) || "set".equals(name)) {
                return true;
            }
            if (!namespace.equals(uri)) {
                return false;
            }
            return "div".equals(name) && ("body".equals(parentName) || "div".equals(parentName))
                    || "p".equals(name) && ("body".equals(parentName) || "div".equals(parentName))
                    || ("span".equals(name) || "br".equals(name)) && ("p".equals(parentName) || "span".equals(parentName));
        }

        private void inspectCommonAttributes(Attributes attrs) {
            for (int i = 0; i < attrs.getLength(); i++) {
                String attrUri = attrs.getURI(i);
                String local = attrs.getLocalName(i);
                String qName = attrs.getQName(i);
                String value = attrs.getValue(i);
                if ("backgroundImage".equals(local)) {
                    throw RawTimeline.invalid();
                }
                if (attrUri.isEmpty() && "timeContainer".equals(local) && !"par".equals(value)) {
                    throw RawTimeline.invalid();
                }
                if ((TTM.equals(attrUri) || qName.startsWith("ttm:"))
                        && ("agent".equals(local) || "role".equals(local))) {
                    metadata.put("AgentDeclared", "true");
                }
            }
        }

        private void parseRootAttributes(Attributes attrs) {
            String timeBase = attrs.getValue(TTP, "timeBase");
            if (timeBase != null && !"media".equals(timeBase)) {
                throw RawTimeline.invalid();
            }
            String lang = attrValue(attrs, XML, "lang");
            if (lang != null && !lang.isEmpty() && languageHeader(lang)) {
                metadata.put("Language", lang);
            }
            float frameRate = 30f;
            String frameRateStr = attrs.getValue(TTP, "frameRate");
            if (frameRateStr != null) {
                frameRate = parsePositiveFloat(frameRateStr);
            }
            float multiplier = 1f;
            String multStr = attrs.getValue(TTP, "frameRateMultiplier");
            if (multStr != null) {
                String[] parts = WHITESPACE.split(multStr.trim());
                if (parts.length != 2) {
                    throw RawTimeline.invalid();
                }
                int num = parsePositiveInt(parts[0]);
                int den = parsePositiveInt(parts[1]);
                multiplier = (float) num / (float) den;
            }
            int subFrameRate = 1;
            String subFrameStr = attrs.getValue(TTP, "subFrameRate");
            if (subFrameStr != null) {
                subFrameRate = parsePositiveInt(subFrameStr);
            }
            int tickRate = 1;
            String tickStr = attrs.getValue(TTP, "tickRate");
            if (tickStr != null) {
                tickRate = parsePositiveInt(tickStr);
            }
            float effectiveFrameRate = frameRate * multiplier;
            if (Float.isNaN(effectiveFrameRate) || Float.isInfinite(effectiveFrameRate) || effectiveFrameRate <= 0f) {
                throw RawTimeline.invalid();
            }
            timing = new TimingProfile(effectiveFrameRate, subFrameRate, tickRate);
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            Frame frame = stack.peek();
            if (frame == null || frame.ignoredSubtree || currentParagraph == null) {
                return;
            }
            if (("p".equals(frame.name) || "span".equals(frame.name)) && length > 0) {
                if (textPart == null) {
                    textPart = appendPart(frame, false);
                }
                textPart.value.append(chars, start, length);
            }
        }

        @Override
        public void endElement(String uri, String name, String qualified) {
            Frame frame = stack.pop();
            textPart = null;
            if (frame == currentParagraph) {
                emitParagraph(frame);
                currentParagraph = null;
                parts = null;
            }
        }

        private void emitParagraph(Frame pFrame) {
            if (pFrame.endUs == null || pFrame.endUs <= pFrame.startUs || parts == null || parts.isEmpty()) {
                return;
            }
            List<Segment> normalized = normalizeParts(pFrame, parts);
            if (normalized.isEmpty()) {
                return;
            }
            StringBuilder mappingText = new StringBuilder();
            for (Segment segment : normalized) {
                mappingText.append(segment.text);
            }
            if (mappingText.length() == 0) {
                return;
            }
            if (cues.size() >= limits.cues) {
                throw RawTimeline.invalid();
            }

            long pStart = RawTimeline.add(pFrame.startUs, offsetUs);
            long pEnd = RawTimeline.add(pFrame.endUs, offsetUs);
            List<RawTimeline.TextRun> runs = buildRuns(pFrame, pStart, pEnd, normalized, mappingText.toString());
            if (runs.size() > limits.textRuns - totalRuns) {
                throw RawTimeline.invalid();
            }
            totalRuns += runs.size();
            cues.add(new RawTimeline.Cue(cues.size(), pFrame.sourceId, pStart, pEnd,
                    mappingText.toString(), runs, false));
        }

        private List<RawTimeline.TextRun> buildRuns(Frame pFrame, long pStart, long pEnd,
                                                    List<Segment> segments, String fullText) {
            if (pFrame.subTimingCorrupted) {
                return singleCueRun(fullText.length(), pStart, pEnd);
            }
            long[] starts = new long[segments.size()];
            long[] ends = new long[segments.size()];
            boolean[] timed = new boolean[segments.size()];
            long lastTimedStart = pStart;
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                Frame owner = segment.owner;
                long activeStart = RawTimeline.add(owner.startUs, offsetUs);
                long activeEnd = owner.endUs == null ? pEnd : RawTimeline.add(owner.endUs, offsetUs);
                if (activeStart < pStart || activeEnd > pEnd || activeEnd <= activeStart) {
                    return singleCueRun(fullText.length(), pStart, pEnd);
                }
                if (owner.inlineTiming && activeStart < lastTimedStart) {
                    // Reversed/out-of-order inline spans inside one <p> degrade only this <p>.
                    return singleCueRun(fullText.length(), pStart, pEnd);
                }
                if (owner.inlineTiming) {
                    lastTimedStart = activeStart;
                }
                starts[i] = activeStart;
                ends[i] = activeEnd;
                timed[i] = owner.inlineTiming;
            }

            List<RawTimeline.TextRun> runs = new ArrayList<>(segments.size());
            int cursor = 0;
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                int nextCursor = cursor + segment.text.length();
                long activeStart = starts[i];
                long activeEnd = ends[i];
                long timingStart = activeStart;
                long timingEnd = activeEnd;
                if (timed[i] && !segment.owner.hasOwnEnd) {
                    long nextWordStart = findNextGreaterTimedStart(starts, timed, i, timingStart);
                    if (nextWordStart > timingStart && nextWordStart < timingEnd) {
                        timingEnd = nextWordStart;
                    }
                }
                RawTimeline.TimingKind kind = timed[i]
                        ? RawTimeline.TimingKind.TTML_INTERVAL
                        : RawTimeline.TimingKind.CUE_INTERVAL;
                runs.add(new RawTimeline.TextRun(cursor, nextCursor, activeStart, activeEnd,
                        timingStart, timingEnd, kind, segment.lineBreak));
                cursor = nextCursor;
            }
            return runs;
        }

        private static long findNextGreaterTimedStart(long[] starts, boolean[] timed, int fromIndex, long currentStart) {
            for (int j = fromIndex + 1; j < starts.length; j++) {
                if (timed[j] && starts[j] > currentStart) {
                    return starts[j];
                }
            }
            return Long.MIN_VALUE;
        }

        private static List<RawTimeline.TextRun> singleCueRun(int length, long startUs, long endUs) {
            List<RawTimeline.TextRun> single = new ArrayList<>(1);
            single.add(new RawTimeline.TextRun(0, length, startUs, endUs, startUs, endUs,
                    RawTimeline.TimingKind.CUE_INTERVAL, false));
            return single;
        }

        private static List<Segment> normalizeParts(Frame pFrame, List<Part> rawParts) {
            List<Segment> out = new ArrayList<>();
            boolean atLineStart = true;
            Frame pendingSpaceOwner = null;
            for (Part part : rawParts) {
                if (part.lineBreak) {
                    pendingSpaceOwner = null;
                    if (!out.isEmpty()) {
                        out.add(new Segment(part.owner, "\n", true));
                    }
                    atLineStart = true;
                    continue;
                }
                String raw = part.value.toString();
                if (raw.isEmpty()) {
                    continue;
                }
                if (part.owner.preserveSpace) {
                    if (pendingSpaceOwner != null) {
                        out.add(new Segment(pendingSpaceOwner, " ", false));
                        pendingSpaceOwner = null;
                    }
                    out.add(new Segment(part.owner, raw, false));
                    atLineStart = raw.charAt(raw.length() - 1) == '\n';
                    continue;
                }
                StringBuilder chunk = new StringBuilder();
                for (int i = 0; i < raw.length(); i++) {
                    char c = raw.charAt(i);
                    if (isXmlWhitespace(c)) {
                        if (!atLineStart && pendingSpaceOwner == null && chunk.length() == 0) {
                            pendingSpaceOwner = part.owner;
                        } else if (chunk.length() > 0 && chunk.charAt(chunk.length() - 1) != ' ') {
                            chunk.append(' ');
                        }
                    } else {
                        if (pendingSpaceOwner != null) {
                            if (pendingSpaceOwner == part.owner) {
                                chunk.insert(0, ' ');
                            } else {
                                out.add(new Segment(pendingSpaceOwner, " ", false));
                            }
                            pendingSpaceOwner = null;
                        }
                        chunk.append(c);
                        atLineStart = false;
                    }
                }
                if (chunk.length() > 0) {
                    boolean trailingSpace = chunk.charAt(chunk.length() - 1) == ' ';
                    if (trailingSpace) {
                        chunk.setLength(chunk.length() - 1);
                        if (chunk.length() > 0) {
                            out.add(new Segment(part.owner, chunk.toString(), false));
                        }
                        if (pendingSpaceOwner == null) {
                            pendingSpaceOwner = part.owner;
                        }
                    } else {
                        out.add(new Segment(part.owner, chunk.toString(), false));
                    }
                }
            }
            // Strip trailing lineBreak-only tail if <p> ended with <br/>.
            while (!out.isEmpty() && out.get(out.size() - 1).lineBreak) {
                out.remove(out.size() - 1);
            }
            return out;
        }

        @Override
        public void endDocument() {
            if (namespace == null || !bodySeen || !stack.isEmpty()) {
                throw RawTimeline.invalid();
            }
        }

        @Override
        public void processingInstruction(String target, String data) {
            throw RawTimeline.invalid();
        }

        @Override
        public void skippedEntity(String name) {
            throw RawTimeline.invalid();
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) {
            throw RawTimeline.invalid();
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            throw RawTimeline.invalid();
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseUri, String systemId) {
            throw RawTimeline.invalid();
        }

        @Override
        public InputSource getExternalSubset(String name, String baseUri) {
            throw RawTimeline.invalid();
        }

        @Override
        public void comment(char[] chars, int start, int length) {
            countNode();
            textPart = null;
        }

        @Override
        public void startCDATA() {
            countNode();
            textPart = null;
        }

        @Override
        public void endCDATA() {
            textPart = null;
        }

        @Override
        public void warning(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }

    private static String attrValue(Attributes attrs, String uri, String local) {
        String value = attrs.getValue(uri, local);
        if (value != null) {
            return value;
        }
        return "space".equals(local) ? attrs.getValue("xml:space")
                : "lang".equals(local) ? attrs.getValue("xml:lang")
                : "id".equals(local) ? attrs.getValue("xml:id") : null;
    }

    private static long timeUs(String value, TimingProfile profile) {
        String trimmed = value.trim();
        Matcher clock = CLOCK_TIME.matcher(trimmed);
        BigDecimal micros;
        if (clock.matches()) {
            BigDecimal seconds = new BigDecimal(clock.group(1)).multiply(BigDecimal.valueOf(3600L))
                    .add(BigDecimal.valueOf(Long.parseLong(clock.group(2)) * 60L))
                    .add(BigDecimal.valueOf(Long.parseLong(clock.group(3))));
            if (clock.group(4) != null) {
                seconds = seconds.add(new BigDecimal(clock.group(4)));
            }
            if (clock.group(5) != null) {
                BigDecimal frameRate = BigDecimal.valueOf(profile.effectiveFrameRate);
                seconds = seconds.add(new BigDecimal(clock.group(5)).divide(frameRate, 9, RoundingMode.HALF_UP));
                if (clock.group(6) != null) {
                    BigDecimal sub = new BigDecimal(clock.group(6));
                    BigDecimal denom = frameRate.multiply(BigDecimal.valueOf(profile.subFrameRate));
                    seconds = seconds.add(sub.divide(denom, 9, RoundingMode.HALF_UP));
                }
            }
            micros = seconds.multiply(BigDecimal.valueOf(1_000_000L));
        } else {
            Matcher offset = OFFSET_TIME.matcher(trimmed);
            if (!offset.matches()) {
                throw RawTimeline.invalid();
            }
            BigDecimal raw = new BigDecimal(offset.group(1));
            String unit = offset.group(2);
            switch (unit) {
                case "h":
                    micros = raw.multiply(BigDecimal.valueOf(3_600_000_000L));
                    break;
                case "m":
                    micros = raw.multiply(BigDecimal.valueOf(60_000_000L));
                    break;
                case "s":
                    micros = raw.multiply(BigDecimal.valueOf(1_000_000L));
                    break;
                case "ms":
                    micros = raw.multiply(BigDecimal.valueOf(1_000L));
                    break;
                case "f":
                    micros = raw.multiply(BigDecimal.valueOf(1_000_000L))
                            .divide(BigDecimal.valueOf(profile.effectiveFrameRate), 0, RoundingMode.HALF_UP);
                    break;
                case "t":
                    micros = raw.multiply(BigDecimal.valueOf(1_000_000L))
                            .divide(BigDecimal.valueOf(profile.tickRate), 0, RoundingMode.HALF_UP);
                    break;
                default:
                    throw RawTimeline.invalid();
            }
        }
        try {
            return micros.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException overflow) {
            throw RawTimeline.invalid();
        }
    }

    private static float parsePositiveFloat(String value) {
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed <= 0f) {
                throw RawTimeline.invalid();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw RawTimeline.invalid();
        }
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw RawTimeline.invalid();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw RawTimeline.invalid();
        }
    }

    private static boolean languageHeader(String value) {
        if (value.length() < 2 || value.length() > 128) {
            return false;
        }
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('-', start);
            if (end < 0) {
                end = value.length();
            }
            int length = end - start;
            if (length < (start == 0 ? 2 : 1) || length > 8) {
                return false;
            }
            for (int i = start; i < end; i++) {
                char c = value.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (start > 0 && c >= '0' && c <= '9'))) {
                    return false;
                }
            }
            if (end == value.length()) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private static boolean isXmlWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static Long min(Long first, Long second) {
        if (first == null) return second;
        if (second == null) return first;
        return Math.min(first, second);
    }
}
