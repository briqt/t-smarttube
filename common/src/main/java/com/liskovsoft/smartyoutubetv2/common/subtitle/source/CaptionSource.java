package com.liskovsoft.smartyoutubetv2.common.subtitle.source;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Caption evidence, not a track-selection policy, spoken-language guarantee, or cache key. */
public final class CaptionSource {
    public enum TextKind {
        NON_ASR_CANDIDATE,
        ASR_CANDIDATE,
        UNKNOWN
    }

    public enum TranslationKind {
        DIRECT,
        TRANSLATED,
        UNKNOWN
    }

    public enum Problem {
        INVALID_URL,
        INVALID_LANGUAGE,
        MISSING_SOURCE_LANGUAGE,
        DUPLICATE_QUERY_PARAMETER,
        CATALOG_LANGUAGE_MISMATCH,
        CATALOG_TYPE_WITHOUT_URL_KIND,
        CATALOG_TYPE_MISMATCH,
        UNRECOGNIZED_KIND
    }

    private final TextKind mTextKind;
    private final TranslationKind mTranslationKind;
    private final String mSourceLanguage;
    private final String mTranslationLanguage;
    private final String mCatalogLanguage;
    private final boolean mTimingOptimized;
    private final Set<Problem> mProblems;

    CaptionSource(TextKind textKind, TranslationKind translationKind, String sourceLanguage,
                  String translationLanguage, String catalogLanguage, boolean timingOptimized,
                  EnumSet<Problem> problems) {
        mTextKind = textKind;
        mTranslationKind = translationKind;
        mSourceLanguage = sourceLanguage;
        mTranslationLanguage = translationLanguage;
        mCatalogLanguage = catalogLanguage;
        mTimingOptimized = timingOptimized;
        mProblems = Collections.unmodifiableSet(EnumSet.copyOf(problems));
    }

    public TextKind getTextKind() {
        return mTextKind;
    }

    public TranslationKind getTranslationKind() {
        return mTranslationKind;
    }

    /** Returns the unique URL lang candidate, or null; catalog conflicts remain in getProblems(). */
    public String getSourceLanguage() {
        return mSourceLanguage;
    }

    /** Returns the explicit tlang language, or null when absent or ambiguous. */
    public String getTranslationLanguage() {
        return mTranslationLanguage;
    }

    public String getCatalogLanguage() {
        return mCatalogLanguage;
    }

    /** A unique dubbing-transcript hint. False does not exclude dubbing. */
    public boolean hasTimingOptimizedVariant() {
        return mTimingOptimized;
    }

    public Set<Problem> getProblems() {
        return mProblems;
    }
}
