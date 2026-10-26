package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import static org.junit.Assert.*;

public class AsrPaintSafetyTest {
    @Test
    public void aRetimedOldRowIsNotEvidenceOfAnUntimedDisplayEcho() {
        String input = "WEBVTT\n\n00:01.000 --> 00:02.000\nold\nnew<00:01.500> line\n\n" +
                "00:02.000 --> 00:02.010\nnew line\n\n" +
                "00:02.010 --> 00:03.000\nn<00:02.100>ew line\nlast<00:02.500> line\n";
        RawTimeline raw = parse(input);
        AsrTranscriptPlanner.Analysis analysis = prepare(raw);
        assertFalse("New internal times in the carried-looking row may describe a new occurrence", analysis.normalized);
        assertEquals(3, analysis.groups.getGroups().size());
        assertEquals("old\nnew line", analysis.groups.getText(0));
        assertEquals("new line", analysis.groups.getText(1));
        assertEquals("new line\nlast line", analysis.groups.getText(2));
        for (MappedTranscript.Atom atom : analysis.transcript.getAtoms()) {
            assertEquals(1, atom.origins.size());
            assertFalse(atom.origins.get(0).echo);
        }
    }

    @Test
    public void aRetimedFirstRowKeepsItsOwnPrimaryAndDoesNotBorrowThePreviousShortHold() {
        String input = "WEBVTT\n\n00:00.990 --> 00:01.000\nold\n\n" +
                "00:01.000 --> 00:02.000\no<00:01.100>ld\nnew<00:01.500> line\n\n" +
                "00:02.000 --> 00:02.010\nnew line\n\n" +
                "00:02.010 --> 00:03.000\nnew line\nlast<00:02.500> line\n";
        AsrTranscriptPlanner.Analysis analysis = prepare(parse(input));
        assertTrue("The later unambiguous paint-on link can still be enhanced", analysis.normalized);
        assertEquals(2, analysis.groups.getGroups().size());
        assertEquals("old", analysis.groups.getText(0));
        assertEquals("old\nnew line\nlast line", analysis.groups.getText(1));
        assertEquals(0, analysis.transcript.getAtoms().get(0).origins.get(0).cueIndex);
        assertEquals(1, analysis.transcript.getAtoms().get(1).origins.get(0).cueIndex);
        assertFalse(analysis.transcript.getAtoms().get(1).origins.get(0).echo);
    }

    private static RawTimeline parse(String source) {
        return RawWebvttParser.parse(source.getBytes(Charset.forName("UTF-8")), 0,
                new RawTimeline.Limits(1048576, 4096, 32768));
    }
    private static AsrTranscriptPlanner.Analysis prepare(RawTimeline raw) {
        MediaSubtitle subtitle = (MediaSubtitle) Proxy.newProxyInstance(MediaSubtitle.class.getClassLoader(),
                new Class<?>[]{MediaSubtitle.class}, (proxy, method, args) ->
                        "getBaseUrl".equals(method.getName()) ? "https://example.invalid/timedtext?lang=en&kind=asr" : null);
        return AsrTranscriptPlanner.prepare(raw, CaptionSourceClassifier.classify(subtitle),
                new RawTimeline.Limits(1048576, 4096, 32768), new SemanticGroupPlanner.Limits(4096, 32768, 32768));
    }
}
