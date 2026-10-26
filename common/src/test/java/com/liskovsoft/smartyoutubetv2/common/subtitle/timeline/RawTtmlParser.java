package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Test-only, fail-closed XML timing subset, NOT a general TTML decoder or native event projection.
 * Supports finite p intervals, par body/div/p/span, timed span/br, media time, and default XML
 * whitespace only. Inline intervals are fragments, not words or measured speech durations. Unknown
 * semantic markup (including styling/head, seq and xml:space=preserve) is rejected.
 * Synthetic unnamespaced trees and body-direct p are accepted; this is not a TTML schema validator.
 * SAX security controls are mandatory: an incompatible XML runtime fails closed, without fallback.
 */
final class RawTtmlParser {
    static final int MAX_DEPTH = 32;
    static final int MAX_NODES = 2048;
    private static final String TT = "http://www.w3.org/ns/ttml";
    private static final String PARAMETER = TT + "#parameter";
    private static final String XML = "http://www.w3.org/XML/1998/namespace";
    private static final Pattern CLOCK = Pattern.compile("([0-9]{2,}):([0-9]{2}):([0-9]{2}(?:\\.[0-9]+)?)");
    private static final Pattern OFFSET = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)");
    private static final Pattern ENCODING = Pattern.compile("\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1");

    private RawTtmlParser() {
    }

    static RawTimeline parse(byte[] source, long effectiveOffsetUs) {
        if (source == null || source.length == 0 || source.length > RawTimeline.MAX_DOCUMENT_BYTES) {
            throw RawTimeline.invalid();
        }
        byte[] bytes = source.clone();
        String xml = RawTimeline.decodeUtf8(bytes);
        if (xml.startsWith("\uFEFF")) {
            xml = xml.substring(1);
        }
        // Reader input prevents an XML declaration from switching away from strict UTF-8 bytes.
        int declarationEnd = xml.startsWith("<?xml") ? xml.indexOf("?>") : -1;
        if (declarationEnd >= 0) {
            Matcher encoding = ENCODING.matcher(xml.substring(0, declarationEnd));
            if (encoding.find() && !"UTF-8".equalsIgnoreCase(encoding.group(2))) {
                throw RawTimeline.invalid();
            }
        }
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setXIncludeAware(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
            Handler handler = new Handler(effectiveOffsetUs);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return new RawTimeline(bytes, handler.cues);
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            // Never retain the parser's source-bearing message, system ID, or cause.
            throw RawTimeline.invalid();
        }
    }

    private static long timeUs(String value) {
        Matcher clock = CLOCK.matcher(value);
        BigDecimal micros;
        if (clock.matches()) {
            int minutes = Integer.parseInt(clock.group(2));
            BigDecimal seconds = new BigDecimal(clock.group(3));
            if (minutes > 59 || seconds.compareTo(BigDecimal.valueOf(60)) >= 0) {
                throw RawTimeline.invalid();
            }
            micros = new BigDecimal(clock.group(1)).multiply(BigDecimal.valueOf(3_600_000_000L))
                    .add(BigDecimal.valueOf(minutes * 60_000_000L))
                    .add(seconds.multiply(BigDecimal.valueOf(1_000_000L)));
        } else {
            Matcher offset = OFFSET.matcher(value);
            if (!offset.matches()) {
                throw RawTimeline.invalid();
            }
            String unit = offset.group(2);
            long scale = "ms".equals(unit) ? 1_000L : "s".equals(unit) ? 1_000_000L
                    : "m".equals(unit) ? 60_000_000L : 3_600_000_000L;
            micros = new BigDecimal(offset.group(1)).multiply(BigDecimal.valueOf(scale));
        }
        // Reject both overflow and sub-microsecond loss instead of rounding/truncating silently.
        return micros.longValueExact();
    }

    private static Long min(Long first, Long second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.min(first, second);
    }

    private static boolean timed(String name) {
        return "body".equals(name) || "div".equals(name) || "p".equals(name)
                || "span".equals(name) || "br".equals(name);
    }

    private static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static void attributes(String name, Attributes attributes) {
        for (int i = 0; i < attributes.getLength(); i++) {
            String uri = attributes.getURI(i);
            String local = attributes.getLocalName(i);
            String value = attributes.getValue(i);
            if (XML.equals(uri) && ("id".equals(local) || "lang".equals(local)
                    || ("space".equals(local) && "default".equals(value)))) {
                continue;
            }
            if (uri.isEmpty() && timed(name) && ("begin".equals(local) || "end".equals(local)
                    || "dur".equals(local) || (!"br".equals(name)
                    && "timeContainer".equals(local) && "par".equals(value)))) {
                continue;
            }
            if ("tt".equals(name) && PARAMETER.equals(uri) && "timeBase".equals(local)
                    && "media".equals(value)) {
                continue;
            }
            throw RawTimeline.invalid();
        }
    }

    private static final class Frame {
        final String name;
        final long startUs;
        final Long endUs; // null is indefinite; Long.MAX_VALUE remains a valid finite endpoint.
        final int sourceIndex;
        final String sourceId;
        final boolean inlineTiming;

        Frame(String name, Frame parent, Attributes attributes, int sourceIndex) {
            this.name = name;
            this.sourceIndex = sourceIndex;
            sourceId = attributes.getValue(XML, "id");
            long syncbase = parent == null ? 0 : parent.startUs;
            String begin = attributes.getValue("", "begin");
            startUs = RawTimeline.add(syncbase, begin == null ? 0 : timeUs(begin));
            String end = attributes.getValue("", "end");
            String dur = attributes.getValue("", "dur");
            inlineTiming = (parent != null && parent.inlineTiming)
                    || (("span".equals(name) || "br".equals(name))
                    && (begin != null || end != null || dur != null));
            Long ownEnd = end == null ? null : RawTimeline.add(syncbase, timeUs(end));
            if (dur != null) {
                ownEnd = min(ownEnd, RawTimeline.add(startUs, timeUs(dur)));
            }
            // An intrinsically empty/reversed interval is invalid; ancestor clipping may hide one.
            if (ownEnd != null && ownEnd <= startUs) {
                throw RawTimeline.invalid();
            }
            endUs = min(ownEnd, parent == null ? null : parent.endUs);
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

    private static final class Handler extends DefaultHandler2 {
        final List<RawTimeline.Cue> cues = new ArrayList<>();
        final Deque<Frame> stack = new ArrayDeque<>();
        final long offsetUs;
        String namespace;
        boolean bodySeen;
        int paragraphs;
        int nodes;
        int textRuns;
        List<Part> parts;
        Part textPart;

        Handler(long offsetUs) {
            this.offsetUs = offsetUs;
        }

        private void node() {
            if (++nodes > MAX_NODES) {
                throw RawTimeline.invalid();
            }
        }

        private Part part(Frame owner, boolean lineBreak) {
            if (++textRuns > RawTimeline.MAX_TEXT_RUNS) {
                throw RawTimeline.invalid();
            }
            Part part = new Part(owner, lineBreak);
            parts.add(part);
            return part;
        }

        @Override
        public void startElement(String uri, String name, String qualified, Attributes attrs) {
            node();
            if (stack.size() >= MAX_DEPTH) {
                throw RawTimeline.invalid();
            }
            Frame parent = stack.peek();
            String parentName = parent == null ? "" : parent.name;
            if (parent == null) {
                if (namespace != null || !"tt".equals(name) || !(uri.isEmpty() || TT.equals(uri))) {
                    throw RawTimeline.invalid();
                }
                namespace = uri;
            } else if (!namespace.equals(uri)
                    || !("body".equals(name) && "tt".equals(parentName) && !bodySeen
                    || "div".equals(name) && ("body".equals(parentName) || "div".equals(parentName))
                    || "p".equals(name) && ("body".equals(parentName) || "div".equals(parentName))
                    || ("span".equals(name) || "br".equals(name))
                    && ("p".equals(parentName) || "span".equals(parentName)))) {
                throw RawTimeline.invalid();
            }
            attributes(name, attrs);
            if ("body".equals(name)) {
                bodySeen = true;
            }
            int index = -1;
            if ("p".equals(name)) {
                if (paragraphs >= RawTimeline.MAX_CUES) {
                    throw RawTimeline.invalid();
                }
                index = paragraphs++;
                parts = new ArrayList<>();
            }
            Frame frame = new Frame(name, parent, attrs, index);
            if ("p".equals(name) && frame.endUs == null) {
                throw RawTimeline.invalid();
            }
            stack.push(frame);
            textPart = null;
            if ("br".equals(name) && frame.endUs > frame.startUs) {
                part(frame, true).value.append('\n');
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            Frame frame = stack.peek();
            String name = frame == null ? "" : frame.name;
            if ("p".equals(name) || "span".equals(name)) {
                if (length > 0 && frame.endUs > frame.startUs) {
                    // Entity expansion and SAX buffer boundaries are not text-node boundaries.
                    if (textPart == null) {
                        textPart = part(frame, false);
                    }
                    textPart.value.append(chars, start, length);
                }
            } else {
                for (int i = start; i < start + length; i++) {
                    if ("br".equals(name) || !whitespace(chars[i])) {
                        throw RawTimeline.invalid();
                    }
                }
            }
        }

        @Override
        public void endElement(String uri, String name, String qualified) {
            Frame frame = stack.pop();
            textPart = null;
            if ("p".equals(name)) {
                long start = RawTimeline.add(frame.startUs, offsetUs);
                long end = RawTimeline.add(frame.endUs, offsetUs);
                if (frame.endUs > frame.startUs) {
                    StringBuilder mappingText = new StringBuilder();
                    List<RawTimeline.TextRun> runs = new ArrayList<>();
                    for (Part part : parts) {
                        int textStart = mappingText.length();
                        mappingText.append(part.value);
                        long activeStart = RawTimeline.add(part.owner.startUs, offsetUs);
                        long activeEnd = RawTimeline.add(part.owner.endUs, offsetUs);
                        runs.add(new RawTimeline.TextRun(textStart, mappingText.length(),
                                activeStart, activeEnd, activeStart, activeEnd,
                                part.owner.inlineTiming ? RawTimeline.TimingKind.TTML_INTERVAL
                                        : RawTimeline.TimingKind.CUE_INTERVAL, part.lineBreak));
                    }
                    cues.add(new RawTimeline.Cue(frame.sourceIndex, frame.sourceId, start, end,
                            mappingText.toString(), runs, true));
                }
                parts = null;
            }
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
            node();
            textPart = null;
        }

        @Override
        public void startCDATA() {
            node();
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
}
