// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "mapped.hpp"
#include <algorithm>
#include <array>
#include <map>
#include <optional>

namespace clarity {
// AVL nodes live in the mapping. Links and values are file offsets, never
// process addresses. Opening an index needs only its saved root offset.
struct alignas(8) IndexNode {
    int64_t number;
    MappedString text;
    uint64_t value, left, right;
    uint32_t height, reserved;
};
struct alignas(8) IndexWrite { uint64_t offset; IndexNode node; };
static_assert(sizeof(IndexNode) == 56 && sizeof(IndexWrite) == 64);
using IndexChanges = std::map<uint64_t, IndexNode>;

class MappedIndexView {
  protected:
    const MappedArena &arena;
    const uint64_t &end;
    const IndexChanges *changes;
    IndexNode node(uint64_t offset) const {
        IndexNode n;
        if (changes && changes->contains(offset)) n = changes->at(offset);
        else n = arena.get<IndexNode>(offset, end);
        if (!n.height || n.height > 64)
            throw Error("Invalid Clarity mapped index height");
        return n;
    }
    static void depth(unsigned value) {
        if (value >= 64) throw Error("Invalid Clarity mapped index depth");
    }
  public:
    MappedIndexView(const MappedArena &a, const uint64_t &e, const IndexChanges *c = nullptr)
        : arena(a), end(e), changes(c) {}
    void checkRoot(uint64_t root) const { if (root) (void)node(root); }
    uint64_t findString(uint64_t root, std::string_view key) const {
        for (unsigned steps = 0; root; ++steps) {
            depth(steps);
            const auto n = node(root);
            const auto cmp = key.compare(arena.get(n.text, end));
            if (!cmp) return n.value;
            root = cmp < 0 ? n.left : n.right;
        }
        return 0;
    }
    // Greatest key <= key (inclusive), or < key (exclusive).
    std::optional<IndexNode> floor(uint64_t root, int64_t key, bool inclusive = true) const {
        std::optional<IndexNode> found;
        for (unsigned steps = 0; root; ++steps) {
            depth(steps);
            const auto n = node(root);
            if (n.number < key || (inclusive && n.number == key)) {
                found = n;
                root = n.right;
            } else root = n.left;
        }
        return found;
    }
    // Least key >= key (inclusive), or > key (exclusive).
    std::optional<IndexNode> ceiling(uint64_t root, int64_t key, bool inclusive = true) const {
        std::optional<IndexNode> found;
        for (unsigned steps = 0; root; ++steps) {
            depth(steps);
            const auto n = node(root);
            if (n.number > key || (inclusive && n.number == key)) {
                found = n;
                root = n.left;
            } else root = n.right;
        }
        return found;
    }
    // The bounded traversal stack is temporary. Nodes/keys remain mapped;
    // no set, map, vector or heap tree is reconstructed to traverse this index.
    template<class F> void visit(uint64_t root, F action) const {
        std::array<uint64_t, 64> stack{};
        unsigned used = 0;
        uint64_t visited = 0;
        while (root || used) {
            while (root) {
                depth(used);
                stack[used++] = root;
                root = node(root).left;
            }
            root = stack[--used];
            const auto n = node(root); // Copy one node before action can remap.
            if (++visited > end / sizeof(IndexNode))
                throw Error("Cyclic Clarity mapped index");
            if (!action(n)) return;
            root = n.right;
        }
    }
};

class MappedIndexEdit : public MappedIndexView {
    MappedArena &output;
    uint64_t &cursor;
    const uint64_t originalEnd;
    // Scratch for only the nodes modified by THIS transaction. It is discarded
    // after commit and is never reconstructed from the database on opening.
    IndexChanges changed;
    unsigned height(uint64_t p) const { return p ? node(p).height : 0; }
    void store(uint64_t p, IndexNode n) {
        n.height = 1 + std::max(height(n.left), height(n.right));
        if (p >= originalEnd) output.write(p, n, cursor); // Not yet published.
        else changed[p] = n;
    }
    uint64_t rotateLeft(uint64_t p) {
        auto a = node(p);
        const auto q = a.right;
        auto b = node(q);
        a.right = b.left;
        store(p, a);
        b.left = p;
        store(q, b);
        return q;
    }
    uint64_t rotateRight(uint64_t p) {
        auto a = node(p);
        const auto q = a.left;
        auto b = node(q);
        a.left = b.right;
        store(p, a);
        b.right = p;
        store(q, b);
        return q;
    }
    uint64_t balance(uint64_t p, IndexNode n) {
        store(p, n);
        const int difference = int(height(n.left)) - int(height(n.right));
        if (difference > 1) {
            auto left = node(n.left);
            if (height(left.left) < height(left.right)) {
                n.left = rotateLeft(n.left);
                store(p, n);
            }
            return rotateRight(p);
        }
        if (difference < -1) {
            auto right = node(n.right);
            if (height(right.right) < height(right.left)) {
                n.right = rotateRight(n.right);
                store(p, n);
            }
            return rotateLeft(p);
        }
        return p;
    }
    uint64_t insert(uint64_t root, const IndexNode &entry, bool stringKey, unsigned level) {
        depth(level);
        if (!root) return output.put(cursor, entry);
        auto n = node(root);
        const int cmp = stringKey ? arena.get(entry.text, end).compare(arena.get(n.text, end))
                                 : (entry.number > n.number) - (entry.number < n.number);
        if (!cmp) {
            if (n.value != entry.value) { n.value = entry.value; store(root, n); }
            return root;
        }
        if (cmp < 0) n.left = insert(n.left, entry, stringKey, level + 1);
        else n.right = insert(n.right, entry, stringKey, level + 1);
        return balance(root, n);
    }
    uint64_t erase(uint64_t root, int64_t key, unsigned level) {
        depth(level);
        if (!root) return 0;
        auto n = node(root);
        if (key < n.number) n.left = erase(n.left, key, level + 1);
        else if (key > n.number) n.right = erase(n.right, key, level + 1);
        else {
            if (!n.left) return n.right;
            if (!n.right) return n.left;
            auto successor = ceiling(n.right, key);
            if (!successor) throw Error("Invalid Clarity mapped successor");
            n.number = successor->number;
            n.text = successor->text;
            n.value = successor->value;
            n.right = erase(n.right, n.number, level + 1);
        }
        return balance(root, n);
    }
  public:
    MappedIndexEdit(MappedArena &a, uint64_t &e)
        : MappedIndexView(a, e, &changed), output(a), cursor(e), originalEnd(e) {}
    uint64_t putNumber(uint64_t root, int64_t key, uint64_t value) {
        IndexNode n{}; n.number = key; n.value = value; n.height = 1;
        return insert(root, n, false, 0);
    }
    uint64_t putString(uint64_t root, MappedString key, uint64_t value) {
        IndexNode n{}; n.text = key; n.value = value; n.height = 1;
        return insert(root, n, true, 0);
    }
    uint64_t eraseNumber(uint64_t root, int64_t key) { return erase(root, key, 0); }
    const IndexChanges &writes() const { return changed; }
};
} // namespace clarity
