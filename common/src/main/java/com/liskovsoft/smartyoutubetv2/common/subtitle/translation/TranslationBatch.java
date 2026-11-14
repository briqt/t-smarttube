package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

        private Item(int groupIndex, String source) {
            this.groupIndex = groupIndex;
            id = "g" + groupIndex;
            this.source = source;
        }
    }

    public final String sourceLanguage;
    public final String targetLanguage;
    public final String context;
    public final List<String> priorLines;
    public final String nextLine;
    public final Limits limits;
    private final SemanticGroupPlanner.Plan mPlan;
    private final List<Item> mItems;

    /** Language/context are explicit caller inputs, not inferred from metadata or display names. */
    public TranslationBatch(SemanticGroupPlanner.Plan plan, List<Integer> indices, String sourceLanguage,
                            String targetLanguage, String context, Limits limits) {
        this(plan, indices, sourceLanguage, targetLanguage, context, limits, Collections.emptyList(), "");
    }

    /** Worker-prepared batch reference context. The budget counts video context plus reference lines once per batch. */
    TranslationBatch(SemanticGroupPlanner.Plan plan, List<Integer> indices, String sourceLanguage,
                     String targetLanguage, String context, Limits limits, List<String> priorLines, String nextLine) {
        if (plan == null || indices == null || limits == null || priorLines == null || indices.size() > limits.items ||
                !language(targetLanguage) || sourceLanguage != null && !language(sourceLanguage) || context == null || !validText(context) ||
                context.codePointCount(0, context.length()) > limits.contextCodePoints) throw invalid();
        int contextCharacters = context.codePointCount(0, context.length());
        List<String> prior = new ArrayList<>(priorLines.size());
        for (String line : priorLines) {
            if (line == null || line.length() > 2L * limits.contextCodePoints || !validText(line)) throw invalid();
            int points = line.codePointCount(0, line.length());
            if (points > limits.contextCodePoints - contextCharacters) throw invalid();
            contextCharacters += points;
            prior.add(line);
        }
        String normalizedNext = nextLine == null ? "" : nextLine;
        if (normalizedNext.length() > 2L * limits.contextCodePoints || !validText(normalizedNext)) throw invalid();
        int nextPoints = normalizedNext.codePointCount(0, normalizedNext.length());
        if (nextPoints > limits.contextCodePoints - contextCharacters) throw invalid();

        List<Item> items = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int characters = 0;
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= plan.getGroups().size() || !seen.add(index)) throw invalid();
            String source = plan.getText(index);
            characters += source.codePointCount(0, source.length());
            if (characters > limits.sourceCodePoints) throw invalid();
            if (hasContent(source)) {
                items.add(new Item(index, source));
            }
        }
        mPlan = plan;
        mItems = Collections.unmodifiableList(items);
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.context = context;
        this.priorLines = Collections.unmodifiableList(prior);
        this.nextLine = normalizedNext;
        this.limits = limits;
    }

    private TranslationBatch(TranslationBatch parent, List<Item> items) {
        mPlan = parent.mPlan;
        mItems = Collections.unmodifiableList(new ArrayList<>(items));
        sourceLanguage = parent.sourceLanguage;
        targetLanguage = parent.targetLanguage;
        context = parent.context;
        boolean headMatches = !items.isEmpty() && !parent.mItems.isEmpty() && items.get(0) == parent.mItems.get(0);
        boolean tailMatches = !items.isEmpty() && !parent.mItems.isEmpty()
                && items.get(items.size() - 1) == parent.mItems.get(parent.mItems.size() - 1);
        priorLines = headMatches ? parent.priorLines : Collections.emptyList();
        nextLine = tailMatches ? parent.nextLine : "";
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
