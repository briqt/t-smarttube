package com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;

import java.util.Collections;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.util.List;

/** Opaque original-caption GET with the existing player transport. Contains sensitive request context. */
public final class NativeCaptionRequest {
    public final CaptionSource source;
    private final DataSource.Factory mFactory;
    private final DataSpec mSpec;
    private final String mMime;

    private NativeCaptionRequest(DataSource.Factory factory, Uri uri, String mime, String visitorCookie, CaptionSource source) {
        mFactory = factory;
        mMime = mime;
        // Same GET/range/Cookie shape as native DASH/SABR single-sample subtitle chunks.
        mSpec = new DataSpec(uri, DataSpec.HTTP_METHOD_GET, null, 0, 0, C.LENGTH_UNSET, null, 0,
                visitorCookie == null ? Collections.emptyMap() : Collections.singletonMap("Cookie", visitorCookie));
        this.source = source;
    }

    /**
     * No association means use current subtitles, not disable translation. Never constructs a URL by
     * removing tlang or other signed parameters. Only unique, actual-catalog, same-video text-track matches.
     */
    public static NativeCaptionRequest find(String videoId, List<MediaSubtitle> subtitles, Format selected,
                                            DataSource.Factory factory, String visitorCookie, boolean standaloneVodClock) {
        if (!standaloneVodClock || videoId == null || videoId.isEmpty() || subtitles == null ||
                subtitles.size() > 512 || selected == null || factory == null ||
                !("text/vtt".equals(selected.sampleMimeType) || "application/ttml+xml".equals(selected.sampleMimeType))) return null;
        try {
            MediaSubtitle current = null;
            for (MediaSubtitle subtitle : subtitles) {
                if (subtitle == null) return null;
                String name = subtitle.getName() == null ? subtitle.getLanguageCode() : subtitle.getName();
                if (Util.areEqual(selected.id, subtitle.getVssId()) &&
                        Util.areEqual(selected.language, Util.normalizeLanguageCode(name)) &&
                        Util.areEqual(selected.sampleMimeType, subtitle.getMimeType())) {
                    if (current != null) return null;
                    current = subtitle;
                }
            }
            if (current == null || current.getVssId() == null) return null;
            CaptionSource selectedSource = CaptionSourceClassifier.classify(current);
            if (selectedSource.getTranslationKind() != CaptionSource.TranslationKind.TRANSLATED ||
                    !selectedSource.getProblems().isEmpty() || selectedSource.getSourceLanguage() == null ||
                    selectedSource.getTextKind() == CaptionSource.TextKind.UNKNOWN || selectedSource.hasTimingOptimizedVariant()) return null;
            Uri currentUri = Uri.parse(current.getBaseUrl());
            if (!captionUri(currentUri, videoId)) return null;
            Uri found = null;
            CaptionSource originalSource = null;
            for (MediaSubtitle subtitle : subtitles) {
                if (!selected.sampleMimeType.equals(subtitle.getMimeType()) || !current.getVssId().equals(subtitle.getVssId())) continue;
                CaptionSource candidate = CaptionSourceClassifier.classify(subtitle);
                if (candidate.getTranslationKind() != CaptionSource.TranslationKind.DIRECT || !candidate.getProblems().isEmpty() ||
                        candidate.hasTimingOptimizedVariant() || candidate.getTextKind() != selectedSource.getTextKind() ||
                        candidate.getSourceLanguage() == null || !candidate.getSourceLanguage().equalsIgnoreCase(selectedSource.getSourceLanguage())) continue;
                Uri uri = Uri.parse(subtitle.getBaseUrl());
                if (!captionUri(uri, videoId) || !uri.getHost().equalsIgnoreCase(currentUri.getHost())) continue;
                if (found != null && !found.equals(uri)) return null;
                found = uri;
                originalSource = candidate;
            }
            return found == null ? null : new NativeCaptionRequest(factory, found, selected.sampleMimeType, visitorCookie, originalSource);
        } catch (RuntimeException ignored) {
            // Provider getters and signed URLs must not escape through exceptions/diagnostics.
            return null;
        }
    }

    private static boolean captionUri(Uri uri, String videoId) {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null ||
                uri.getPort() != -1 && uri.getPort() != 443 || !"/api/timedtext".equals(uri.getPath()) ||
                !("www.youtube.com".equalsIgnoreCase(uri.getHost()) || "youtube.com".equalsIgnoreCase(uri.getHost())) ||
                !uri.getQueryParameters("variant").isEmpty()) return false;
        String query = uri.getEncodedQuery();
        if (query == null || query.length() > 16384) return false;
        int videos = 0;
        try {
            for (String part : query.split("&")) {
                int separator = part.indexOf('=');
                String key = URLDecoder.decode(separator < 0 ? part : part.substring(0, separator), "UTF-8");
                if ("variant".equals(key)) return false;
                if ("v".equals(key)) {
                    if (++videos != 1 || separator < 0 || !videoId.equals(URLDecoder.decode(part.substring(separator + 1), "UTF-8"))) return false;
                }
            }
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) { return false; }
        return videos == 1;
    }

    DataSource createDataSource() { return mFactory.createDataSource(); }
    DataSpec dataSpec() { return mSpec; }
    String mime() { return mMime; }
}
