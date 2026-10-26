package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable request items from one exact plan. IDs are local, never persistent cache identities. */
public final class TranslationBatch {
    public static final class Limits {
        public final int items;
        public final int sourceCodePoints;
        public final int contextCodePoints;
        public final int translatedCodePoints;

        public Limits(int items, int sourceCodePoints, int contextCodePoints, int translatedCodePoints) {
            if (items <= 0 || items > 64 || sourceCodePoints <= 0 || sourceCodePoints > 131072 ||
                    contextCodePoints < 0 || contextCodePoints > 32768 ||
                    translatedCodePoints <= 0 || translatedCodePoints > 131072) throw invalid();
            this.items = items;
            this.sourceCodePoints = sourceCodePoints;
            this.contextCodePoints = contextCodePoints;
            this.translatedCodePoints = translatedCodePoints;
        }
    }

    public static final class Item {
        public final String id;
        public final int groupIndex;
        public final String source;
        /** Frozen data for this item only; recovery subsets reuse this exact immutable value. */
        public final String context;

        private Item(int groupIndex, String source, String context) {
            this.groupIndex = groupIndex;
            id = "g" + groupIndex;
            this.source = source;
            this.context = context;
        }
    }

    public final String sourceLanguage;
    public final String targetLanguage;
    public final String context;
    public final Limits limits;
    private final SemanticGroupPlanner.Plan mPlan;
    private final List<Item> mItems;

    /** Language/context are explicit caller inputs, not inferred from metadata or display names. */
    public TranslationBatch(SemanticGroupPlanner.Plan plan, List<Integer> indices, String sourceLanguage,
                            String targetLanguage, String context, Limits limits) {
        this(plan, indices, sourceLanguage, targetLanguage, context, limits, Collections.emptyMap());
    }

    /** Worker-prepared local contexts. The budget counts every actually sent copy, including labels. */
    TranslationBatch(SemanticGroupPlanner.Plan plan, List<Integer> indices, String sourceLanguage,
                     String targetLanguage, String context, Limits limits, Map<Integer, String> localContexts) {
        if (plan == null || indices == null || limits == null || localContexts == null || indices.size() > limits.items ||
                !language(targetLanguage) || sourceLanguage != null && !language(sourceLanguage) || context == null || !validText(context) ||
                context.codePointCount(0, context.length()) > limits.contextCodePoints) throw invalid();
        List<Item> items = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int characters = 0;
        int contextCharacters = context.codePointCount(0, context.length());
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= plan.getGroups().size() || !seen.add(index)) throw invalid();
            String source = plan.getText(index);
            characters += source.codePointCount(0, source.length());
            if (characters > limits.sourceCodePoints) throw invalid();
            String local = localContexts.containsKey(index) ? localContexts.get(index) : "";
            if (local == null || local.length() > 2L * limits.contextCodePoints || !validText(local)) throw invalid();
            if (hasContent(source)) {
                int localPoints = local.codePointCount(0, local.length());
                if (localPoints > limits.contextCodePoints - contextCharacters) throw invalid();
                contextCharacters += localPoints;
                items.add(new Item(index, source, local));
            }
        }
        mPlan = plan;
        mItems = Collections.unmodifiableList(items);
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.context = context;
        this.limits = limits;
    }

    private TranslationBatch(TranslationBatch parent, List<Item> items) {
        mPlan = parent.mPlan;
        mItems = Collections.unmodifiableList(new ArrayList<>(items));
        sourceLanguage = parent.sourceLanguage;
        targetLanguage = parent.targetLanguage;
        context = parent.context;
        limits = parent.limits;
    }

    public boolean isFor(SemanticGroupPlanner.Plan plan) {
        return mPlan == plan;
    }

    public List<Item> getItems() {
        return mItems;
    }

    TranslationBatch subset(List<Item> items) {
        if (items.isEmpty() || !mItems.containsAll(items) || new HashSet<>(items).size() != items.size()) throw invalid();
        return new TranslationBatch(this, items);
    }

    /** Context-only whitespace/punctuation still belongs to the plan, but must not spend a request. */
    static boolean hasContent(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int type = Character.getType(cp);
            if (Character.isLetterOrDigit(cp) || type == Character.OTHER_SYMBOL ||
                    type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean language(String value) {
        if (value == null || value.length() < 2 || value.length() > 128) return false;
        int primaryEnd = value.indexOf('-');
        if (primaryEnd < 0) primaryEnd = value.length();
        if (primaryEnd < 2 || primaryEnd > 8) return false;
        for (int i = 0; i < primaryEnd; i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')) return false;
        }
        int segment = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-') {
                if (segment == 0 || segment > 8) return false;
                segment = 0;
            } else {
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) return false;
                segment++;
            }
        }
        return segment > 0 && segment <= 8;
    }

    static boolean validText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid caption translation data");
    }
}
