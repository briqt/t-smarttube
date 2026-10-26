package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** Explicit connection check: a synthetic sentence, one request, no persistence or playback input. */
public final class CaptionConnectionProbe {
    private static final ChatTranslationClient CLIENT = new ChatTranslationClient(new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build());

    public static ChatTranslationClient.Job create(CaptionConnectionEdit edit,
            CaptionConnectionStore.Snapshot saved, String target) {
        return create(edit, saved, target, CLIENT);
    }

    static ChatTranslationClient.Job create(CaptionConnectionEdit edit,
            CaptionConnectionStore.Snapshot saved, String target, ChatTranslationClient client) {
        if (edit == null || saved == null || client == null || edit.operation != CaptionConnectionEdit.Operation.TEST ||
                edit.revision != saved.revision || edit.connection == null) throw TranslationBatch.invalid();
        ChatTranslationClient.Credentials credentials = edit.testCredentials(saved, target);
        if (credentials == null) throw TranslationBatch.invalid();
        RawTimeline raw = RawWebvttParser.parse("WEBVTT\n\n00:00.000 --> 00:01.000\nConnection test.\n"
                .getBytes(Charset.forName("UTF-8")), 0, new RawTimeline.Limits(1024, 8, 16));
        MappedTranscript transcript = MappedTranscript.fromRaw(raw, 16);
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(transcript, new SemanticGroupPlanner.Limits(8, 16, 128));
        TranslationBatch batch = new TranslationBatch(plan, Collections.singletonList(0), "en", target, "",
                new TranslationBatch.Limits(1, 128, 0, 256));
        ChatCompletionAdapter.Config wire = edit.connection.wire;
        ChatCompletionAdapter.Config probe = new ChatCompletionAdapter.Config(wire.endpoint.toString(), wire.model,
                Math.min(64, wire.maxOutputTokens), Math.min(8192, wire.requestBytes), Math.min(8192, wire.responseBytes),
                wire.tokenField, wire.thinking);
        return client.newJob(batch, probe, credentials, 1);
    }

    private CaptionConnectionProbe() { }
}
