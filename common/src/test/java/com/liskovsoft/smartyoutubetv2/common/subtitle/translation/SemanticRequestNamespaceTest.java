package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.google.android.exoplayer2.text.Cue;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.ComparisonFailure;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

/**
 * Single-variable completed-cache contracts with one actual ScopedCredentials implementation.
 * Context is supplied explicitly by this fixture; this does not test production neighbor acquisition.
 * Mutation cases corrupt only a newly created test Profile's digest, never production code/state.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class SemanticRequestNamespaceTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String SOURCE = "bank";
    private static final String SOURCE_LANGUAGE = "en";
    private static final String TARGET_LANGUAGE = "zh";
    private static final String CONTEXT = "river bank context";
    private static final String DISPLAY_ASSERTION = "Changed semantic dimension must reach the native sink";

    private enum Dimension {
        SOURCE_LANGUAGE("fr", SemanticRequestNamespaceTest.TARGET_LANGUAGE, SemanticRequestNamespaceTest.CONTEXT),
        TARGET_LANGUAGE(SemanticRequestNamespaceTest.SOURCE_LANGUAGE, "ja", SemanticRequestNamespaceTest.CONTEXT),
        CONTEXT(SemanticRequestNamespaceTest.SOURCE_LANGUAGE, SemanticRequestNamespaceTest.TARGET_LANGUAGE,
                "financial bank context");

        final String sourceLanguage;
        final String targetLanguage;
        final String context;

        Dimension(String sourceLanguage, String targetLanguage, String context) {
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.context = context;
        }
    }

    @Test
    public void equalSemanticsOnANewProfileHitWithTheSameScopedOwnerAndFreshFrame() throws Exception {
        try (Harness h = new Harness()) {
            h.completeThenReconfigureEqualProfile();
        }
    }

    @Test
    public void sourceLanguageAloneMissesAndDisplaysItsFinalBodySentinel() throws Exception {
        assertChangedNamespace(Dimension.SOURCE_LANGUAGE, false);
    }

    @Test
    public void targetLanguageAloneMissesAndDisplaysItsFinalBodySentinel() throws Exception {
        assertChangedNamespace(Dimension.TARGET_LANGUAGE, false);
    }

    @Test
    public void explicitContextAloneMissesAndDisplaysItsFinalBodySentinel() throws Exception {
        assertChangedNamespace(Dimension.CONTEXT, false);
    }

    @Test
    public void oracleRejectsATestOnlyDigestThatOmitsTheSourceLanguageChange() throws Exception {
        assertOmissionIsCaught(Dimension.SOURCE_LANGUAGE);
    }

    @Test
    public void oracleRejectsATestOnlyDigestThatOmitsTheTargetLanguageChange() throws Exception {
        assertOmissionIsCaught(Dimension.TARGET_LANGUAGE);
    }

    @Test
    public void oracleRejectsATestOnlyDigestThatOmitsTheExplicitContextChange() throws Exception {
        assertOmissionIsCaught(Dimension.CONTEXT);
    }

    private static void assertChangedNamespace(Dimension dimension, boolean omitDimension) throws Exception {
        try (Harness h = new Harness()) {
            TranslationCoordinator.Frame old = h.completeThenReconfigureEqualProfile();
            TranslationCoordinator.Profile unchanged = h.currentProfile;
            TranslationCoordinator.Profile changed = h.profile(dimension.sourceLanguage,
                    dimension.targetLanguage, dimension.context);
            h.assertOnlyDimensionChanged(unchanged, changed, dimension);
            assertFalse("Production semantics must distinguish this one changed field",
                    Arrays.equals(unchanged.semantics, changed.semantics));
            if (omitDimension) {
                // Controlled counterexample: since all other fields are identical, copying the
                // original digest simulates omitting precisely this dimension from cache identity.
                assertEquals(unchanged.semantics.length, changed.semantics.length);
                System.arraycopy(unchanged.semantics, 0, changed.semantics, 0, changed.semantics.length);
            }

            h.engine.configure(changed);
            h.currentProfile = changed;
            assertFalse("A new Profile must reject the old Frame even when its cache key collides",
                    h.engine.deliver(old, ignored -> fail("Old Frame reached the sink")));
            h.queue.drain();
            h.assertStableDomain();
            TranslationCoordinator.Frame current = h.frame();
            assertNotSame(old, current);
            String actualDisplay = h.display(current);
            if (omitDimension) {
                assertEquals("The controlled omission must actually reuse the wrong cached value", 1, h.httpCalls);
                assertEquals(1, h.created);
                assertEquals(1, h.owner.keyReads);
                assertEquals(displayText(sentinel(SOURCE_LANGUAGE, TARGET_LANGUAGE, CONTEXT, SOURCE)), actualDisplay);
            }
            // This SAME behavioral assertion must fail in each controlled omission case. It is
            // intentionally before request-count checks: the oracle catches wrong visible content.
            assertEquals(DISPLAY_ASSERTION, displayText(sentinel(dimension.sourceLanguage,
                    dimension.targetLanguage, dimension.context, SOURCE)), actualDisplay);
            assertEquals("A genuine one-field semantic change needs a new transport execution", 2, h.httpCalls);
            assertEquals(2, h.created);
            assertEquals(2, h.owner.keyReads);
            assertEquals(0, h.dnsCalls);
            assertTrue(h.queue.tasks.isEmpty());
            h.assertEncodedInput(1, dimension.sourceLanguage, dimension.targetLanguage, dimension.context);
            assertTrue(h.engine.deliver(current, ignored -> { }));
        }
    }

    private static void assertOmissionIsCaught(Dimension dimension) throws Exception {
        try {
            assertChangedNamespace(dimension, true);
        } catch (ComparisonFailure caught) {
            // Do not accept an arbitrary failure, owner change, disabled engine, malformed payload,
            // or exhausted budget as mutation detection: require the exact wrong-display comparison.
            assertTrue(caught.getMessage().startsWith(DISPLAY_ASSERTION));
            assertEquals(displayText(sentinel(dimension.sourceLanguage, dimension.targetLanguage,
                    dimension.context, SOURCE)), caught.getExpected());
            assertEquals(displayText(sentinel(SOURCE_LANGUAGE, TARGET_LANGUAGE, CONTEXT, SOURCE)), caught.getActual());
            return;
        }
        fail("The same behavior oracle did not detect the test-only omitted dimension: " + dimension);
    }

    private static String sentinel(String sourceLanguage, String targetLanguage, String context, String source) {
        return "sentinel[source=" + sourceLanguage + ";target=" + targetLanguage +
                ";context=" + context + ";text=" + source + "]";
    }

    private static String displayText(String translation) {
        return SOURCE + "\n" + translation;
    }

    private static final class Owner implements TranslationCoordinator.ScopedCredentials {
        final TranslationCoordinator.CredentialScope scope = new TranslationCoordinator.CredentialScope();
        int keyReads;
        @Override public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return scope; }
        @Override public String getKey() { keyReads++; return "synthetic-namespace-fixture-credential"; }
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int drained = 0;
            while (!tasks.isEmpty()) {
                assertTrue("Unexpected unbounded fixture scheduling", ++drained <= 8);
                tasks.remove(0).run();
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        final Owner owner = new Owner();
        final CaptionSession session = new CaptionSession();
        final CaptionSession.CacheScope sourceScope = new CaptionSession.CacheScope();
        final CaptionSession.Track track = session.selectTrack(0, null, sourceScope);
        final CaptionSession.InputClock clock = new CaptionSession.InputClock("text/vtt", 0, 0);
        final RawTimeline raw = RawWebvttParser.parse(("WEBVTT\n\n00:01.000 --> 00:05.000\n" +
                SOURCE + "\n").getBytes(UTF8), 0, new RawTimeline.Limits(4096, 8, 16));
        final MappedTranscript mapped = MappedTranscript.fromRaw(raw, 16);
        final SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped,
                new SemanticGroupPlanner.Limits(8, 16, 16));
        final ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config(
                "https://namespace-fixture.invalid/chat/completions", "namespace-fixture-model",
                128, 8192, 8192, ChatCompletionAdapter.TokenField.MAX_TOKENS,
                ChatCompletionAdapter.Thinking.OMIT);
        final TranslationBatch.Limits batchLimits = new TranslationBatch.Limits(2, 256, 128, 1024);
        final Queue queue = new Queue();
        final List<JSONObject> encodedInputs = new ArrayList<>();
        final ChatTranslationClient client;
        final TranslationCoordinator engine;
        final CaptionSession.Snapshot snapshot;
        TranslationCoordinator.Profile currentProfile;
        int created;
        int httpCalls;
        int dnsCalls;

        Harness() {
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan, clock, null));
            snapshot = session.getSnapshot();
            OkHttpClient transport = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .dns(host -> { dnsCalls++; throw new AssertionError("Fixture must never resolve DNS"); })
                    .addInterceptor(chain -> {
                        // Application interceptor returns before routing/DNS. No real endpoint/Key.
                        httpCalls++;
                        try {
                            assertEquals("POST", chain.request().method());
                            assertEquals(wire.endpoint, chain.request().url());
                            Buffer buffer = new Buffer();
                            chain.request().body().writeTo(buffer);
                            JSONObject body = BoundedJson.object(buffer.readUtf8());
                            assertEquals(wire.model, body.getString("model"));
                            JSONArray messages = body.getJSONArray("messages");
                            assertEquals("system", messages.getJSONObject(0).getString("role"));
                            assertEquals("user", messages.getJSONObject(1).getString("role"));
                            JSONObject input = BoundedJson.object(messages.getJSONObject(1).getString("content"));
                            encodedInputs.add(input);
                            JSONArray translations = new JSONArray();
                            JSONArray items = input.getJSONArray("items");
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                translations.put(new JSONObject().put("id", item.getString("id")).put("text",
                                        sentinel(input.getString("source_language"), input.getString("target_language"),
                                                input.getString("context"), item.getString("source"))));
                            }
                            String content = new JSONObject().put("translations", translations).toString();
                            String envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("finish_reason", "stop")
                                    .put("message", new JSONObject().put("content", content)))).toString();
                            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("offline namespace fixture")
                                    .body(ResponseBody.create(MediaType.parse("application/json"), envelope)).build();
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    }).build();
            client = new ChatTranslationClient(transport);
            engine = new TranslationCoordinator(session, () -> 100L, queue, (batch, profile) -> {
                created++;
                ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
                return new TranslationCoordinator.Work() {
                    @Override public ChatTranslationClient.Result execute() { return job.execute(); }
                    @Override public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(0, 8, 8192, 10000, 2, 50, 32));
        }

        TranslationCoordinator.Profile profile(String sourceLanguage, String targetLanguage, String context) {
            return new TranslationCoordinator.Profile(wire, owner, 7, sourceLanguage, targetLanguage,
                    context, batchLimits, 1);
        }

        TranslationCoordinator.Frame completeThenReconfigureEqualProfile() throws Exception {
            currentProfile = profile(SOURCE_LANGUAGE, TARGET_LANGUAGE, CONTEXT);
            engine.tick(1_000_000, false);
            engine.configure(currentProfile);
            engine.setVisible(true);
            engine.setRequesting(true);
            queue.drain();
            TranslationCoordinator.Frame old = frame();
            String expected = displayText(sentinel(SOURCE_LANGUAGE, TARGET_LANGUAGE, CONTEXT, SOURCE));
            assertEquals(expected, display(old));
            assertEquals(1, httpCalls);
            assertEncodedInput(0, SOURCE_LANGUAGE, TARGET_LANGUAGE, CONTEXT);

            TranslationCoordinator.Profile equal = profile(SOURCE_LANGUAGE, TARGET_LANGUAGE, CONTEXT);
            assertNotSame(currentProfile, equal);
            assertSame(owner, equal.credentials);
            assertSame(currentProfile.cacheScope, equal.cacheScope);
            assertArrayEquals(currentProfile.semantics, equal.semantics);
            engine.configure(equal);
            currentProfile = equal;
            assertFalse(engine.deliver(old, ignored -> fail("Equal semantics must not revive an old Frame")));
            queue.drain();
            TranslationCoordinator.Frame fresh = frame();
            assertNotSame(old, fresh);
            assertEquals(expected, display(fresh));
            assertEquals("New Profile identity alone must not cause another request", 1, httpCalls);
            assertEquals(1, created);
            assertEquals(1, owner.keyReads);
            assertEquals(0, dnsCalls);
            assertTrue(queue.tasks.isEmpty());
            assertStableDomain();
            return fresh;
        }

        void assertOnlyDimensionChanged(TranslationCoordinator.Profile before,
                                        TranslationCoordinator.Profile after, Dimension dimension) {
            assertSame(owner, before.credentials);
            assertSame(owner, after.credentials);
            assertSame(owner.scope, before.cacheScope);
            assertSame(owner.scope, after.cacheScope);
            assertTrue(owner.scope.active());
            assertEquals(before.credentialRevision, after.credentialRevision);
            assertSame(before.wire, after.wire);
            assertSame(before.batchLimits, after.batchLimits);
            assertEquals(before.maxCalls, after.maxCalls);
            assertEquals(dimension == Dimension.SOURCE_LANGUAGE, !before.sourceLanguage.equals(after.sourceLanguage));
            assertEquals(dimension == Dimension.TARGET_LANGUAGE, !before.targetLanguage.equals(after.targetLanguage));
            assertEquals(dimension == Dimension.CONTEXT, !before.context.equals(after.context));
            assertStableDomain();
        }

        void assertStableDomain() {
            assertSame(snapshot, session.getSnapshot());
            assertTrue(session.isCurrent(snapshot));
            assertSame(track, snapshot.track);
            assertSame(sourceScope, snapshot.track.cacheScope);
            assertSame(raw, snapshot.timeline);
            assertSame(mapped, snapshot.transcript);
            assertSame(plan, snapshot.groups);
            assertSame(clock, snapshot.clock);
        }

        TranslationCoordinator.Frame frame() {
            TranslationCoordinator.Frame frame = engine.prepareFrame();
            assertNotNull("The engine must remain requesting and visible, not pass by disabling AI", frame);
            assertEquals(1, frame.items.size());
            assertEquals(SOURCE, frame.items.get(0).original);
            assertNotNull("A completed translation must actually be available", frame.items.get(0).translation);
            return frame;
        }

        String display(TranslationCoordinator.Frame frame) {
            AtomicReference<List<Cue>> applied = new AtomicReference<>();
            assertTrue(engine.deliver(frame, latest -> applied.set(NativeCueComposer.compose(
                    Collections.singletonList(new Cue(SOURCE)), SOURCE, latest.items.get(0).translation,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST, 2048))));
            assertNotNull(applied.get());
            assertEquals(1, applied.get().size());
            return applied.get().get(0).text.toString();
        }

        void assertEncodedInput(int index, String sourceLanguage, String targetLanguage, String context) throws Exception {
            JSONObject input = encodedInputs.get(index);
            assertEquals(sourceLanguage, input.getString("source_language"));
            assertEquals(targetLanguage, input.getString("target_language"));
            assertEquals(context, input.getString("context"));
            assertEquals(1, input.getJSONArray("items").length());
            assertEquals(SOURCE, input.getJSONArray("items").getJSONObject(0).getString("source"));
        }

        @Override public void close() {
            engine.close();
            session.close();
        }
    }
}
