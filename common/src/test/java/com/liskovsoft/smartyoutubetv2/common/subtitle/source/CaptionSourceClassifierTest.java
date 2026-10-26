package com.liskovsoft.smartyoutubetv2.common.subtitle.source;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.Problem;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.TextKind;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.TranslationKind;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CaptionSourceClassifierTest {
    private static final String BASE_URL = "https://example.invalid/timedtext";

    @Test
    public void directNonAsrRemainsOnlyACandidate() {
        CaptionSource source = classify("lang=en&fmt=vtt", "en", null);
        assertEquals(TextKind.NON_ASR_CANDIDATE, source.getTextKind());
        assertEquals(TranslationKind.DIRECT, source.getTranslationKind());
        assertEquals("en", source.getSourceLanguage());
        assertNull(source.getTranslationLanguage());
        assertFalse(source.hasTimingOptimizedVariant());
        assertTrue(source.getProblems().isEmpty());
    }

    @Test
    public void asrAndTranslationAreIndependent() {
        CaptionSource source = classify("lang=en&kind=asr&tlang=zh-Hans", "zh-Hans", "asr");
        assertEquals(TextKind.ASR_CANDIDATE, source.getTextKind());
        assertEquals(TranslationKind.TRANSLATED, source.getTranslationKind());
        assertEquals("en", source.getSourceLanguage());
        assertEquals("zh-hans", source.getTranslationLanguage());
        assertTrue(source.getProblems().isEmpty());
    }

    @Test
    public void directAsrIsNotMistakenForTranslation() {
        CaptionSource source = classify("lang=en&kind=asr&fmt=ttml", "en", "asr");
        assertEquals(TextKind.ASR_CANDIDATE, source.getTextKind());
        assertEquals(TranslationKind.DIRECT, source.getTranslationKind());
    }

    @Test
    public void legacyTranslationCanRetainNonAsrType() {
        CaptionSource source = classify("lang=en&tlang=zh-Hans&fmt=vtt", "zh-Hans", null);
        assertEquals(TextKind.NON_ASR_CANDIDATE, source.getTextKind());
        assertEquals(TranslationKind.TRANSLATED, source.getTranslationKind());
        assertEquals("en", source.getSourceLanguage());
        assertEquals("zh-hans", source.getCatalogLanguage());
    }

    @Test
    public void synthesizedAsrTypeDoesNotProveParentWasAsr() {
        CaptionSource source = classify("lang=en&tlang=zh-Hans&fmt=ttml", "zh-Hans", "asr");
        assertEquals(TextKind.UNKNOWN, source.getTextKind());
        assertEquals(TranslationKind.TRANSLATED, source.getTranslationKind());
        assertTrue(source.getProblems().contains(Problem.CATALOG_TYPE_WITHOUT_URL_KIND));
    }

    @Test
    public void identicalLegacySameLanguageItemsRemainIndistinguishable() {
        CaptionSource parent = classify("lang=en&fmt=vtt", "en", null);
        CaptionSource synthesized = classify("lang=en&fmt=vtt", "en", null);
        assertEquals(parent.getTextKind(), synthesized.getTextKind());
        assertEquals(parent.getTranslationKind(), synthesized.getTranslationKind());
        assertEquals(parent.getSourceLanguage(), synthesized.getSourceLanguage());
        assertEquals(parent.getProblems(), synthesized.getProblems());
    }

    @Test
    public void sameLanguageSyntheticTypeDoesNotCreateAnAsrOrigin() {
        CaptionSource parent = classify("lang=en&fmt=ttml", "en", null);
        CaptionSource synthesized = classify("lang=en&fmt=ttml", "en", "asr");
        assertEquals(TextKind.NON_ASR_CANDIDATE, parent.getTextKind());
        assertEquals(TextKind.UNKNOWN, synthesized.getTextKind());
        assertEquals(TranslationKind.DIRECT, synthesized.getTranslationKind());
    }

    @Test
    public void explicitSameLanguageTlangStillHasTranslationMarker() {
        CaptionSource source = classify("lang=en&tlang=en", "en", null);
        assertEquals(TranslationKind.TRANSLATED, source.getTranslationKind());
        assertEquals("en", source.getTranslationLanguage());
    }

    @Test
    public void dubbingHintIsIndependentOfAsrAndTranslation() {
        CaptionSource source = classify("lang=de&kind=asr&variant=timing-optimized&tlang=en", "en", "asr");
        assertEquals(TextKind.ASR_CANDIDATE, source.getTextKind());
        assertEquals(TranslationKind.TRANSLATED, source.getTranslationKind());
        assertTrue(source.hasTimingOptimizedVariant());
        assertEquals("de", source.getSourceLanguage());
    }

    @Test
    public void languageConflictPreservesBothCandidatesWithoutChoosingATrack() {
        CaptionSource source = classify("lang=en", "fr", null);
        assertEquals("en", source.getSourceLanguage());
        assertEquals("fr", source.getCatalogLanguage());
        assertTrue(source.getProblems().contains(Problem.CATALOG_LANGUAGE_MISMATCH));
    }

    @Test
    public void regionalLanguageIsNotSilentlyEquatedWithPrimaryLanguage() {
        CaptionSource source = classify("lang=en", "en-US", null);
        assertEquals("en-us", source.getCatalogLanguage());
        assertTrue(source.getProblems().contains(Problem.CATALOG_LANGUAGE_MISMATCH));
    }

    @Test
    public void translatedCatalogLanguageIsComparedWithTargetNotSource() {
        CaptionSource source = classify("lang=en&tlang=zh-Hans", "ja", null);
        assertEquals("en", source.getSourceLanguage());
        assertEquals("zh-hans", source.getTranslationLanguage());
        assertEquals("ja", source.getCatalogLanguage());
        assertTrue(source.getProblems().contains(Problem.CATALOG_LANGUAGE_MISMATCH));
    }

    @Test
    public void languageTagCaseIsNormalized() {
        CaptionSource source = classify("lang=EN-us&tlang=ZH-hans", "zh-Hans", null);
        assertEquals("en-us", source.getSourceLanguage());
        assertEquals("zh-hans", source.getTranslationLanguage());
        assertTrue(source.getProblems().isEmpty());
    }

    @Test
    public void japaneseAndKoreanAreNotCollapsedIntoChinese() {
        assertEquals("ja", classify("lang=ja", "ja", null).getSourceLanguage());
        assertEquals("ko", classify("lang=ko", "ko", null).getSourceLanguage());
        assertEquals("zh-hans", classify("lang=zh-Hans", "zh-Hans", null).getSourceLanguage());
    }

    @Test
    public void contradictoryAsrTypeRemainsUnknown() {
        CaptionSource source = classify("lang=en&kind=asr", "en", "other");
        assertEquals(TextKind.UNKNOWN, source.getTextKind());
        assertTrue(source.getProblems().contains(Problem.CATALOG_TYPE_MISMATCH));
    }

    @Test
    public void unrecognizedKindDoesNotFallBackToCatalogAsr() {
        CaptionSource source = classify("lang=en&kind=other", "en", "asr");
        assertEquals(TextKind.UNKNOWN, source.getTextKind());
        assertTrue(source.getProblems().contains(Problem.UNRECOGNIZED_KIND));
    }

    @Test
    public void asrUrlMarkerDoesNotRequireCatalogType() {
        assertEquals(TextKind.ASR_CANDIDATE, classify("lang=en&kind=asr", "en", null).getTextKind());
    }

    @Test
    public void duplicateSourceLanguageDoesNotUseFirstOrLastValue() {
        CaptionSource source = classify("lang=en&lang=fr&kind=asr", "en", "asr");
        assertNull(source.getSourceLanguage());
        assertEquals(TextKind.ASR_CANDIDATE, source.getTextKind());
        assertTrue(source.getProblems().contains(Problem.DUPLICATE_QUERY_PARAMETER));
    }

    @Test
    public void identicalDuplicateTranslationParametersStillRemainAmbiguous() {
        CaptionSource source = classify("lang=en&tlang=zh&tlang=zh", "zh", null);
        assertEquals(TranslationKind.UNKNOWN, source.getTranslationKind());
        assertNull(source.getTranslationLanguage());
        assertEquals("en", source.getSourceLanguage());
        assertTrue(source.getProblems().contains(Problem.DUPLICATE_QUERY_PARAMETER));
    }

    @Test
    public void encodedParameterNamesCannotHideDuplicates() {
        CaptionSource source = classify("lang=en&tlang=zh&%74lang=ja", "zh", null);
        assertEquals(TranslationKind.UNKNOWN, source.getTranslationKind());
        assertTrue(source.getProblems().contains(Problem.DUPLICATE_QUERY_PARAMETER));
        assertNull(classify("lang=en&%6cang=fr", "en", null).getSourceLanguage());
    }

    @Test
    public void duplicateKindDoesNotDiscardIndependentTranslationEvidence() {
        CaptionSource source = classify("lang=en&kind=asr&kind=asr&tlang=zh", "zh", "asr");
        assertEquals(TextKind.UNKNOWN, source.getTextKind());
        assertEquals(TranslationKind.TRANSLATED, source.getTranslationKind());
        assertTrue(source.getProblems().contains(Problem.DUPLICATE_QUERY_PARAMETER));
    }

    @Test
    public void duplicateVariantDoesNotDiscardIndependentAsrEvidence() {
        CaptionSource source = classify("lang=en&kind=asr&variant=timing-optimized&variant=other", "en", "asr");
        assertFalse(source.hasTimingOptimizedVariant());
        assertEquals(TextKind.ASR_CANDIDATE, source.getTextKind());
        assertTrue(source.getProblems().contains(Problem.DUPLICATE_QUERY_PARAMETER));
    }

    @Test
    public void emptyOrBareTranslationParameterIsNotDirect() {
        for (String query : new String[]{"lang=en&tlang", "lang=en&tlang="}) {
            CaptionSource source = classify(query, "en", null);
            assertEquals(TranslationKind.UNKNOWN, source.getTranslationKind());
            assertTrue(source.getProblems().contains(Problem.INVALID_LANGUAGE));
        }
    }

    @Test
    public void missingSourceLanguageIsNotReplacedByTranslatedCatalogLanguage() {
        CaptionSource source = classify("tlang=zh", "zh", null);
        assertNull(source.getSourceLanguage());
        assertEquals("zh", source.getTranslationLanguage());
        assertTrue(source.getProblems().contains(Problem.MISSING_SOURCE_LANGUAGE));
    }

    @Test
    public void missingUrlLanguageDoesNotInventAnOriginFromCatalog() {
        CaptionSource source = classify("fmt=vtt", "en", null);
        assertNull(source.getSourceLanguage());
        assertEquals("en", source.getCatalogLanguage());
        assertTrue(source.getProblems().contains(Problem.MISSING_SOURCE_LANGUAGE));
    }

    @Test
    public void unknownAndMalformedLanguageTagsAreNotConfidentLanguages() {
        for (String language : new String[]{"und", "", "en_US", "English*", "en+US"}) {
            CaptionSource source = classify("lang=" + language, null, null);
            assertNull(source.getSourceLanguage());
            assertTrue(source.getProblems().contains(Problem.INVALID_LANGUAGE));
        }
    }

    @Test
    public void encodedSeparatorsDoNotCreateTranslationParameters() {
        CaptionSource source = classify("lang=en%26tlang%3Dzh", null, null);
        assertEquals(TranslationKind.DIRECT, source.getTranslationKind());
        assertNull(source.getSourceLanguage());
        assertTrue(source.getProblems().contains(Problem.INVALID_LANGUAGE));
    }

    @Test
    public void unrelatedValuesAndFragmentsAreNotTranslationParameters() {
        CaptionSource source = classify("lang=en&name=tlang%3Dzh#tlang=ja", "en", null);
        assertEquals(TranslationKind.DIRECT, source.getTranslationKind());
        assertNull(source.getTranslationLanguage());
        assertTrue(source.getProblems().isEmpty());
    }

    @Test
    public void queryValuesAreDecodedOnlyOnce() {
        CaptionSource source = classify("lang=en&tlang=%257A%2568", null, null);
        assertEquals(TranslationKind.UNKNOWN, source.getTranslationKind());
        assertNull(source.getTranslationLanguage());
        assertTrue(source.getProblems().contains(Problem.INVALID_LANGUAGE));
    }

    @Test
    public void invalidUrlsReturnUnknownWithoutThrowing() {
        for (String url : new String[]{null, "", "/timedtext?lang=en", "not a url",
                "file:///timedtext?lang=en", "https://example.invalid/timedtext?lang=%ZZ"}) {
            CaptionSource source = CaptionSourceClassifier.classify(new FakeSubtitle(url, "en", null));
            assertEquals(TranslationKind.UNKNOWN, source.getTranslationKind());
            assertEquals(TextKind.UNKNOWN, source.getTextKind());
            assertTrue(source.getProblems().contains(Problem.INVALID_URL));
        }
        assertEquals(TranslationKind.UNKNOWN, CaptionSourceClassifier.classify(null).getTranslationKind());
    }

    @Test
    public void formatAndOpaqueRequestParametersDoNotChangeClassification() {
        CaptionSource first = classify("lang=en&kind=asr&fmt=vtt&signature=opaque-a&expire=1", "en", "asr");
        CaptionSource second = classify("lang=en&kind=asr&fmt=ttml&signature=opaque-b&expire=2", "en", "asr");
        assertEquals(first.getTextKind(), second.getTextKind());
        assertEquals(first.getTranslationKind(), second.getTranslationKind());
        assertEquals(first.getSourceLanguage(), second.getSourceLanguage());
        assertEquals(first.getProblems(), second.getProblems());
        assertFalse(first.toString().contains("opaque-a"));
        assertFalse(first.toString().contains(BASE_URL));
    }

    @Test
    public void readsOnlyNeededGettersOnceAndRetainsNoMutableInput() {
        FakeSubtitle subtitle = new FakeSubtitle(BASE_URL + "?lang=en&kind=asr", "en", "asr");
        CaptionSource source = CaptionSourceClassifier.classify(subtitle);
        assertEquals(1, subtitle.urlReads);
        assertEquals(1, subtitle.languageReads);
        assertEquals(1, subtitle.typeReads);
        subtitle.url = BASE_URL + "?lang=ja&tlang=zh";
        subtitle.language = "zh";
        subtitle.type = null;
        assertEquals("en", source.getSourceLanguage());
        assertEquals("en", source.getCatalogLanguage());
        assertEquals(TextKind.ASR_CANDIDATE, source.getTextKind());
        assertEquals(TranslationKind.DIRECT, source.getTranslationKind());
    }

    @Test
    public void resultProblemsCannotBeMutated() {
        for (CaptionSource source : new CaptionSource[]{classify("lang=en", "en", null),
                CaptionSourceClassifier.classify(null)}) {
            try {
                source.getProblems().add(Problem.INVALID_URL);
                fail("Classification problems must be immutable");
            } catch (UnsupportedOperationException expected) {
                // Both empty and nonempty results must be immutable.
            }
        }
    }

    @Test
    public void malformedQueryFixtureNeverEscapesAsAnException() {
        Random random = new Random(0x5a1);
        String alphabet = "abcXYZ09&=%?#_:+/[] é";
        for (int i = 0; i < 1000; i++) {
            StringBuilder query = new StringBuilder("lang=");
            for (int j = 0; j < 24; j++) {
                query.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            assertNotNull(classify(query.toString(), null, null));
        }
    }

    @Test
    public void undeterminedLanguageRemainsUnknownWithScriptOrRegion() {
        for (String language : new String[]{"und-Latn", "UND-US", "und-Latn-US"}) {
            assertNull(classify("lang=" + language, null, null).getSourceLanguage());
            CaptionSource translated = classify("lang=en&tlang=" + language, null, null);
            assertEquals(TranslationKind.UNKNOWN, translated.getTranslationKind());
            assertNull(translated.getTranslationLanguage());
            assertTrue(translated.getProblems().contains(Problem.INVALID_LANGUAGE));
        }
    }

    @Test
    public void incompleteExtensionMarkersRemainUnknown() {
        for (String language : new String[]{"en-x", "en-a", "en-u-a", "en--US", "en-"}) {
            CaptionSource source = classify("lang=en&tlang=" + language, null, null);
            assertEquals(TranslationKind.UNKNOWN, source.getTranslationKind());
            assertNull(source.getTranslationLanguage());
            assertTrue(source.getProblems().contains(Problem.INVALID_LANGUAGE));
        }
    }

    @Test
    public void completeExtensionsAndPrivateUseRemainCandidates() {
        for (String language : new String[]{"en-u-ca-gregory", "en-x-a", "zh-Hant-TW"}) {
            CaptionSource source = classify("lang=" + language, language, null);
            assertNotNull(source.getSourceLanguage());
            assertTrue(source.getProblems().isEmpty());
        }
    }

    @Test
    public void veryLongRepeatedSubtagsDoNotOverflow() {
        StringBuilder language = new StringBuilder("en");
        for (int i = 0; i < 10000; i++) {
            language.append("-a");
        }
        CaptionSource source = classify("lang=" + language, null, null);
        assertNull(source.getSourceLanguage());
        assertTrue(source.getProblems().contains(Problem.INVALID_LANGUAGE));
    }

    @Test
    public void veryLongPrivateUseTagIsScannedWithoutRecursion() {
        StringBuilder language = new StringBuilder("en-x");
        for (int i = 0; i < 10000; i++) {
            language.append("-a");
        }
        CaptionSource source = classify("lang=" + language, null, null);
        assertEquals(language.toString(), source.getSourceLanguage());
        assertTrue(source.getProblems().isEmpty());
    }

    private static CaptionSource classify(String query, String language, String type) {
        return CaptionSourceClassifier.classify(new FakeSubtitle(BASE_URL + "?" + query, language, type));
    }

    private static final class FakeSubtitle implements MediaSubtitle {
        String url;
        String language;
        String type;
        int urlReads;
        int languageReads;
        int typeReads;

        FakeSubtitle(String url, String language, String type) {
            this.url = url;
            this.language = language;
            this.type = type;
        }

        @Override
        public String getBaseUrl() {
            urlReads++;
            return url;
        }

        @Override
        public String getLanguageCode() {
            languageReads++;
            return language;
        }

        @Override
        public String getType() {
            typeReads++;
            return type;
        }

        @Override
        public boolean isTranslatable() {
            throw new AssertionError("Classification must not decide track policy");
        }

        @Override
        public String getVssId() {
            throw new AssertionError("vssId is not a unique caption identity");
        }

        @Override
        public String getName() {
            throw new AssertionError("Display names are not caption provenance");
        }

        @Override
        public String getMimeType() {
            throw new AssertionError("MIME type is not caption provenance");
        }

        @Override
        public String getCodecs() {
            throw new AssertionError("Codecs are not caption provenance");
        }
    }
}
