package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verbatim generic fixtures from exoplayer-amzn-2.10.6/library/core/src/test/assets, not YouTube captures. */
public class UpstreamSubtitleFixtureTest {
    @Test
    public void ordinaryUpstreamVttParsesWithoutWordTimeOrRollingIdentity() throws Exception {
        byte[] source = fixture("webvtt/typical");
        RawTimeline raw = RawWebvttParser.parse(source, 0);
        assertEquals(2, raw.getCues().size());
        assertEquals(0, raw.getCues().get(0).startUs);
        assertEquals(1_234_000L, raw.getCues().get(0).endUs);
        assertEquals(2_345_000L, raw.getCues().get(1).startUs);
        assertEquals(3_456_000L, raw.getCues().get(1).endUs);
        assertEquals("This is the first subtitle.", raw.getCues().get(0).text);
        assertEquals("This is the second subtitle.", raw.getCues().get(1).text);
        assertArrayEquals(source, raw.copySource());
        MappedTranscript transcript = RollingTextNormalizer.normalize(raw, null);
        assertEquals(2, transcript.getAtoms().size());
        assertEquals(0, transcript.getAtoms().get(0).flowIndex);
        assertEquals(1, transcript.getAtoms().get(1).flowIndex);
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, atom.origins.get(0).timingKind);
        }
    }

    @Test
    public void numericCueIdsArePreservedWithoutBecomingASharedRollingGroup() throws Exception {
        RawTimeline raw = RawWebvttParser.parse(fixture("webvtt/typical_with_identifiers"), 7);
        assertEquals("1", raw.getCues().get(0).sourceId);
        assertEquals("2", raw.getCues().get(1).sourceId);
        assertEquals(7, raw.getCues().get(0).startUs);
        assertEquals(2_345_007L, raw.getCues().get(1).startUs);
        MappedTranscript transcript = RollingTextNormalizer.normalize(raw, null);
        assertEquals(2, SemanticGroupPlanner.plan(transcript, 100).size());
        assertEquals(1, transcript.getAtoms().get(1).flowIndex);
    }

    @Test
    public void upstreamCrossedTagsAreExplicitlyOutsideTheStrictRawSubset() throws Exception {
        byte[] source = fixture("webvtt/with_tags");
        String text = new String(source, "UTF-8");
        assertTrue(text.contains("<b><i>second</b></i>"));
        assertTrue(text.contains("&lt;fourth&gt; &amp;subtitle."));
        rejected(() -> RawWebvttParser.parse(source, 0));
        // Reject the whole fixture rather than silently retaining the first valid cue.
    }

    @Test
    public void upstreamBitmapDocumentIsWellFormedButOutsideTextOnlyRawSubset() throws Exception {
        byte[] source = fixture("ttml/bitmap_percentage_region.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(source));
        assertEquals("tt", document.getDocumentElement().getLocalName());
        assertEquals(1, document.getElementsByTagNameNS("http://www.w3.org/ns/ttml", "head").getLength());
        assertEquals(0, document.getElementsByTagNameNS("http://www.w3.org/ns/ttml", "p").getLength());
        rejected(() -> RawTtmlParser.parse(source, 0));
        // The first unsupported element is head; this does not separately test region or bitmap decoding.
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream input = UpstreamSubtitleFixtureTest.class.getResourceAsStream("/subtitle/upstream/" + name)) {
            assertNotNull("Upstream fixture resource missing", input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
            fail("Expected explicit unsupported-format rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
        }
    }
}
