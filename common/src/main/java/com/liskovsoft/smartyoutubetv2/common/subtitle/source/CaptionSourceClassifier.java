package com.liskovsoft.smartyoutubetv2.common.subtitle.source;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.Problem;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.TextKind;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.TranslationKind;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Interprets caption metadata without fetching, selecting tracks, or retaining signed URLs. */
public final class CaptionSourceClassifier {
    private CaptionSourceClassifier() {
    }

    public static CaptionSource classify(MediaSubtitle subtitle) {
        String url = subtitle != null ? subtitle.getBaseUrl() : null;
        String catalogLanguageValue = subtitle != null ? subtitle.getLanguageCode() : null;
        String catalogType = subtitle != null ? emptyToNull(subtitle.getType()) : null;
        EnumSet<Problem> problems = EnumSet.noneOf(Problem.class);
        String catalogLanguage = normalizeLanguage(emptyToNull(catalogLanguageValue), problems);
        Map<String, List<String>> query = parseQuery(url, problems);
        if (query == null) {
            return new CaptionSource(TextKind.UNKNOWN, TranslationKind.UNKNOWN, null, null,
                    catalogLanguage, false, problems);
        }

        String sourceLanguage = normalizeLanguage(unique(query, "lang", problems), problems);
        if (sourceLanguage == null) {
            problems.add(Problem.MISSING_SOURCE_LANGUAGE);
        }
        String translationLanguage = normalizeLanguage(unique(query, "tlang", problems), problems);
        TranslationKind translationKind = !query.containsKey("tlang") ? TranslationKind.DIRECT :
                translationLanguage != null ? TranslationKind.TRANSLATED : TranslationKind.UNKNOWN;
        TextKind textKind = classifyTextKind(query, catalogType, problems);
        boolean timingOptimized = "timing-optimized".equals(unique(query, "variant", problems));

        String expectedCatalogLanguage = translationKind == TranslationKind.TRANSLATED ? translationLanguage :
                translationKind == TranslationKind.DIRECT ? sourceLanguage : null;
        if (catalogLanguage != null && expectedCatalogLanguage != null &&
                !catalogLanguage.equals(expectedCatalogLanguage)) {
            problems.add(Problem.CATALOG_LANGUAGE_MISMATCH);
        }
        return new CaptionSource(textKind, translationKind, sourceLanguage, translationLanguage,
                catalogLanguage, timingOptimized, problems);
    }

    private static TextKind classifyTextKind(Map<String, List<String>> query, String catalogType,
                                             EnumSet<Problem> problems) {
        String kind = unique(query, "kind", problems);
        if (!query.containsKey("kind")) {
            if (catalogType == null) {
                return TextKind.NON_ASR_CANDIDATE;
            }
            // A synthesized caption can have type=asr even when its parent is not ASR.
            problems.add("asr".equals(catalogType) ? Problem.CATALOG_TYPE_WITHOUT_URL_KIND :
                    Problem.UNRECOGNIZED_KIND);
            return TextKind.UNKNOWN;
        }
        if (kind == null) {
            return TextKind.UNKNOWN;
        }
        if (!"asr".equals(kind)) {
            problems.add(Problem.UNRECOGNIZED_KIND);
            return TextKind.UNKNOWN;
        }
        if (catalogType != null && !"asr".equals(catalogType)) {
            problems.add(Problem.CATALOG_TYPE_MISMATCH);
            return TextKind.UNKNOWN;
        }
        return TextKind.ASR_CANDIDATE;
    }

    private static Map<String, List<String>> parseQuery(String url, EnumSet<Problem> problems) {
        try {
            URI uri = url != null ? new URI(url) : null;
            if (uri == null || uri.getHost() == null ||
                    !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                problems.add(Problem.INVALID_URL);
                return null;
            }
            Map<String, List<String>> result = new HashMap<>();
            String query = uri.getRawQuery();
            if (query == null) {
                return result;
            }
            // Split before decoding so an encoded ampersand cannot create a new parameter.
            for (String part : query.split("&")) {
                int separator = part.indexOf('=');
                String name = decode(separator >= 0 ? part.substring(0, separator) : part);
                if (!"lang".equals(name) && !"tlang".equals(name) &&
                        !"kind".equals(name) && !"variant".equals(name)) {
                    continue;
                }
                String value = decode(separator >= 0 ? part.substring(separator + 1) : "");
                List<String> values = result.get(name);
                if (values == null) {
                    values = new ArrayList<>();
                    result.put(name, values);
                }
                values.add(value);
            }
            return result;
        } catch (URISyntaxException | IllegalArgumentException | UnsupportedEncodingException e) {
            // The exception can contain the entire signed URL; do not expose it.
            problems.add(Problem.INVALID_URL);
            return null;
        }
    }

    private static String decode(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value, "UTF-8");
    }

    private static String unique(Map<String, List<String>> query, String name, EnumSet<Problem> problems) {
        List<String> values = query.get(name);
        if (values == null) {
            return null;
        }
        // Do not silently use first/last wins, including repeated identical values.
        if (values.size() != 1) {
            problems.add(Problem.DUPLICATE_QUERY_PARAMETER);
            return null;
        }
        return values.get(0);
    }

    private static String normalizeLanguage(String language, EnumSet<Problem> problems) {
        if (language == null) {
            return null;
        }
        if (!isLanguageCandidate(language)) {
            problems.add(Problem.INVALID_LANGUAGE);
            return null;
        }
        return language.toLowerCase(Locale.ROOT);
    }

    /** Conservative structural validation, not a registry lookup or full BCP 47 parser. */
    private static boolean isLanguageCandidate(String language) {
        int primaryEnd = language.indexOf('-');
        if (primaryEnd == -1) {
            primaryEnd = language.length();
        }
        if (primaryEnd < 2 || primaryEnd > 8 ||
                (primaryEnd == 3 && language.regionMatches(true, 0, "und", 0, 3))) {
            return false;
        }
        for (int i = 0; i < primaryEnd; i++) {
            if (!isAsciiLetter(language.charAt(i))) {
                return false;
            }
        }
        if (primaryEnd == language.length()) {
            return true;
        }

        boolean privateUse = false;
        boolean needsValue = false;
        int start = primaryEnd + 1;
        while (start < language.length()) {
            int end = language.indexOf('-', start);
            if (end == -1) {
                end = language.length();
            }
            int length = end - start;
            if (length < 1 || length > 8) {
                return false;
            }
            for (int i = start; i < end; i++) {
                char value = language.charAt(i);
                if (!isAsciiLetter(value) && !(value >= '0' && value <= '9')) {
                    return false;
                }
            }
            if (!privateUse && length == 1) {
                if (needsValue) {
                    return false;
                }
                char singleton = language.charAt(start);
                privateUse = singleton == 'x' || singleton == 'X';
                needsValue = true;
            } else {
                needsValue = false;
            }
            if (end == language.length()) {
                return !needsValue;
            }
            start = end + 1;
        }
        return false;
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
