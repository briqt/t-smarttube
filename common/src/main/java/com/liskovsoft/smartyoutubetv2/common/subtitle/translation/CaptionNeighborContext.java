package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Caption-only, worker-side neighbor preparation. No retained document, provider, or network state. */
final class CaptionNeighborContext {
    static final int MAX_GROUPS = 8192;
    static final int LOCAL_CODE_POINTS = 1024;
    static final int DEPENDENCIES = 4;
    private static final int PRIOR_LIMIT = 3;
    private static final long PRIOR_GAP_US = 20_000_000L;
    private static final long NEXT_GAP_US = 15_000_000L;
    private static final int CONTRACT = 2;

    static final class Signature {
        final byte[] bytes;
        private final int hash;
        Signature(byte[] bytes) { this.bytes = bytes; hash = Arrays.hashCode(bytes); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Signature && Arrays.equals(bytes, ((Signature) other).bytes);
        }
    }

    static final class Dependency {
        final Signature slot;
        final byte[] content;
        final long startUs;
        final long endUs;
        final boolean sent;
        Dependency(Group group, boolean sent) {
            slot = group.slot;
            content = group.base.digest;
            startUs = group.startUs;
            endUs = group.endUs;
            this.sent = sent;
        }
    }

    /** Retain only limited text, fixed signatures and scalars; never Group/Document/Snapshot/Profile. */
    static final class Frozen {
        final List<String> priorLines;
        final String nextLine;
        final Signature target;
        final List<Dependency> dependencies;
        final byte[] fingerprint;
        final int codePoints;
        final int priorCodePoints;
        final int nextCodePoints;
        final boolean associationCertain;
        Frozen(List<String> priorLines, String nextLine, Signature target, List<Dependency> dependencies, boolean associationCertain) {
            this.priorLines = Collections.unmodifiableList(new ArrayList<>(priorLines));
            this.nextLine = nextLine == null ? "" : nextLine;
            int priorPoints = 0;
            for (String line : this.priorLines) {
                priorPoints += line.codePointCount(0, line.length());
            }
            this.target = target;
            this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
            this.associationCertain = associationCertain;
            this.priorCodePoints = priorPoints;
            this.nextCodePoints = this.nextLine.codePointCount(0, this.nextLine.length());
            this.codePoints = this.priorCodePoints + this.nextCodePoints;
            SemanticTranslationCache.Digest digest = new SemanticTranslationCache.Digest();
            digest.number(CONTRACT);
            // Cost identity is the actual local data, not the association/omission dependencies.
            // A changed omitted neighbor may require re-preparation without changing paid semantics.
            digest.number(this.priorLines.size());
            for (String line : this.priorLines) digest.string(line);
            digest.string(this.nextLine);
            fingerprint = digest.finish();
        }
        int dependencyBytes() { return dependencies.size() * 64; } // Two SHA-256 values per dependency.
    }

    /** Temporary current-document index, built at most once per worker preparation, outside locks. */
    static final class Group {
        final int index;
        final SemanticTranslationCache.Key base;
        final Signature slot;
        final String text;
        final int codePoints;
        final long startUs;
        final long endUs;
        int position;
        Group(CaptionSession.Snapshot snapshot, TranslationCoordinator.Profile profile, int index) {
            this.index = index;
            base = SemanticTranslationCache.key(snapshot, profile, index);
            slot = new Signature(SemanticTranslationCache.slot(snapshot, index));
            text = snapshot.groups.getText(index);
            codePoints = text.codePointCount(0, text.length());
            long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
            for (MappedTranscript.Reference ref : snapshot.groups.getReferences(index)) {
                start = Math.min(start, ref.origin.activeStartUs);
                end = Math.max(end, ref.origin.activeEndUs);
            }
            // Ordering bounds only. Never used to create a display interval or fill a source gap.
            startUs = start;
            endUs = end;
        }
    }

    static final class Document {
        final Group[] groups;
        private final List<Group> ordered;
        private final Map<Signature, Group> slots = new HashMap<>();
        private final long firstStartUs;
        Document(CaptionSession.Snapshot snapshot, TranslationCoordinator.Profile profile) {
            int count = snapshot.groups.getGroups().size();
            if (count > MAX_GROUPS) throw TranslationBatch.invalid();
            groups = new Group[count];
            ordered = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Group group = new Group(snapshot, profile, i);
                groups[i] = group;
                if (group.endUs <= group.startUs) continue;
                ordered.add(group);
                if (slots.containsKey(group.slot)) slots.put(group.slot, null); // Non-unique is not a revision ID.
                else slots.put(group.slot, group);
            }
            Collections.sort(ordered, (first, second) -> {
                if (first.startUs != second.startUs) return first.startUs < second.startUs ? -1 : 1;
                if (first.endUs != second.endUs) return first.endUs < second.endUs ? -1 : 1;
                return first.index < second.index ? -1 : first.index == second.index ? 0 : 1;
            });
            for (int i = 0; i < ordered.size(); i++) ordered.get(i).position = i;
            firstStartUs = ordered.isEmpty() ? Long.MAX_VALUE : ordered.get(0).startUs;
        }

        boolean uniqueTarget(int index) { return slots.get(groups[index].slot) == groups[index]; }

        /** Only fixed-size comparisons/map lookups; ambiguous old associations are never guessed. */
        boolean matches(Frozen frozen, int index) {
            Group target = groups[index];
            if (!frozen.associationCertain || !frozen.target.equals(target.slot) || slots.get(target.slot) != target) return false;
            for (Dependency dependency : frozen.dependencies) {
                Group current = slots.get(dependency.slot);
                if (current != null) {
                    if (!Arrays.equals(current.base.digest, dependency.content)) return false;
                } else if (slots.containsKey(dependency.slot) || dependency.endUs > firstStartUs) {
                    // Ambiguous/re-cut/interior/right-hand disappearance is not proved sliding loss.
                    return false;
                }
                // A lost earlier slot is retained as bounded frozen data, not treated as a retraction.
            }
            return true;
        }

        Frozen prepare(int index, int localBudget) {
            Group target = groups[index];
            int budget = Math.min(LOCAL_CODE_POINTS, Math.max(0, localBudget));
            List<Dependency> dependencies = new ArrayList<>();
            List<String> priorReversed = new ArrayList<>(PRIOR_LIMIT);
            String nextLine = "";
            int used = 0;
            boolean certain = slots.get(target.slot) == target;
            if (budget > 0 && target.endUs > target.startUs) {
                Group cursor = target;
                for (int step = 0, pos = target.position - 1; step < PRIOR_LIMIT && pos >= 0; step++, pos--) {
                    Group prior = ordered.get(pos);
                    if (cursor.startUs > saturatedAdd(prior.endUs, PRIOR_GAP_US)) break;
                    int points = prior.text.codePointCount(0, prior.text.length());
                    boolean sent = points <= budget - used;
                    dependencies.add(new Dependency(prior, sent)); // Omitted but observed also invalidates on revision.
                    certain &= slots.get(prior.slot) == prior;
                    if (sent) {
                        priorReversed.add(prior.text);
                        used += points;
                    }
                    cursor = prior;
                }
                int nextPos = target.position + 1;
                if (nextPos < ordered.size()) {
                    Group next = ordered.get(nextPos);
                    if (next.startUs <= saturatedAdd(target.endUs, NEXT_GAP_US)) {
                        int points = next.text.codePointCount(0, next.text.length());
                        boolean sent = points <= budget - used;
                        dependencies.add(new Dependency(next, sent));
                        certain &= slots.get(next.slot) == next;
                        if (sent) {
                            nextLine = next.text;
                            used += points;
                        }
                    }
                }
            }
            Collections.reverse(priorReversed);
            return new Frozen(priorReversed, nextLine, target.slot, dependencies, certain);
        }
    }

    private static long saturatedAdd(long value, long positive) {
        return value > Long.MAX_VALUE - positive ? Long.MAX_VALUE : value + positive;
    }
    private CaptionNeighborContext() { }
}
