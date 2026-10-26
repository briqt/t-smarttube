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
    static final int LOCAL_CODE_POINTS = 512;
    static final int DEPENDENCIES = 4;
    private static final long GAP_US = 15_000_000;
    private static final int CONTRACT = 1;

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
        final String text;
        final Signature target;
        final List<Dependency> dependencies;
        final byte[] fingerprint;
        final int codePoints;
        final boolean associationCertain;
        Frozen(String text, Signature target, List<Dependency> dependencies, boolean associationCertain) {
            this.text = text;
            this.target = target;
            this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
            this.associationCertain = associationCertain;
            codePoints = text.codePointCount(0, text.length());
            SemanticTranslationCache.Digest digest = new SemanticTranslationCache.Digest();
            digest.number(CONTRACT);
            // Cost identity is the actual local data, not the association/omission dependencies.
            // A changed omitted neighbor may require re-preparation without changing paid semantics.
            digest.string(text);
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
            StringBuilder context = new StringBuilder();
            int used = 0;
            boolean certain = slots.get(target.slot) == target;
            if (budget > 0 && target.endUs > target.startUs) {
                // Nearest before/after first, independent of peers, batch size, loading and cache hits.
                for (int distance = 1; distance <= 2; distance++) {
                    for (int direction : new int[]{-1, 1}) {
                        int at = target.position + distance * direction;
                        if (at < 0 || at >= ordered.size()) continue;
                        Group neighbor = ordered.get(at);
                        Group left = direction < 0 ? neighbor : target;
                        Group right = direction < 0 ? target : neighbor;
                        if (right.startUs > saturatedAdd(left.endUs, GAP_US)) continue;
                        String label = direction < 0 ? "[nearby-before; continuity unknown]\n" :
                                "[nearby-after; continuity unknown]\n";
                        int points = label.length() + neighbor.text.codePointCount(0, neighbor.text.length()) + 1;
                        boolean sent = points <= budget - used;
                        dependencies.add(new Dependency(neighbor, sent)); // Omitted but observed also invalidates on revision.
                        certain &= slots.get(neighbor.slot) == neighbor;
                        if (sent) {
                            context.append(label).append(neighbor.text).append('\n');
                            used += points;
                        }
                    }
                }
            }
            return new Frozen(context.toString(), target.slot, dependencies, certain);
        }
    }

    private static long saturatedAdd(long value, long positive) {
        return value > Long.MAX_VALUE - positive ? Long.MAX_VALUE : value + positive;
    }
    private CaptionNeighborContext() { }
}
