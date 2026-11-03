// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "bytes.hpp"
#include <bit>
#include <cstring>
#include <limits>
#include <memory>
#include <type_traits>
namespace clarity {
// Uses Juggluco's Mmap (inout.hpp). No pointers, STL containers or JSON trees
// are written to disk. All persisted layouts have fixed-width fields.
class MappedFile {
    struct Impl;
    std::unique_ptr<Impl> impl;
  public:
    explicit MappedFile(const std::string &path, bool exclusive = false);
    ~MappedFile();
    bool created() const;
    size_t size() const;
    unsigned char *data();
    const unsigned char *data() const;
    void ensure(size_t size);
    void sync(size_t from, size_t to);
    void syncRanges(std::span<const std::pair<size_t, size_t>> ranges);
};
uint32_t mappedChecksum(const void *data, size_t size, uint32_t seed = 0);
struct alignas(8) MappedHeader {
    char magic[8];
    uint32_t version, bodySize;
    uint64_t generation;
    uint32_t checksum, reserved;
};
static_assert(sizeof(MappedHeader) == 32);
// Independent page-aligned slots: data is synced before publishing a new
// checkpoint. An interrupted checkpoint leaves the previous slot usable.
// mmap's normal writeback alone does not provide that commit ordering.
template<class T> class MappedCheckpoint {
    static_assert(std::is_trivially_copyable_v<T>);
    static_assert(std::endian::native == std::endian::little);
    static constexpr size_t stride = ((sizeof(T) + sizeof(MappedHeader) + 65535) / 65536) * 65536;
    MappedFile file;
    uint64_t generation = 0;
    int slot = 1;
    bool failed = false;
    std::string magic;
    bool valid(int index) const {
        const auto *p = file.data() + index * stride;
        MappedHeader h;
        memcpy(&h, p, sizeof h);
        const auto checksum=h.checksum;
        h.checksum=0;
        return !memcmp(h.magic, magic.data(), 8) && h.version == 1 &&
            h.bodySize == sizeof(T) && h.generation &&
            checksum == mappedChecksum(p + sizeof h, sizeof(T),mappedChecksum(&h,sizeof h));
    }
  public:
    MappedCheckpoint(const std::string &path, const char (&tag)[9], const T &initial, bool exclusive = true)
        : file(path, exclusive), magic(tag, 8) {
        if (!file.created() && file.size() < 2 * stride)
            throw Error("Truncated Clarity mapped checkpoint");
        file.ensure(2 * stride);
        bool a = valid(0), b = valid(1);
        if (!a && !b) {
            if (!file.created())
                throw Error("Invalid Clarity mapped checkpoint; upload stopped");
            save(initial);
            return;
        }
        MappedHeader h[2];
        memcpy(h, file.data(), sizeof(MappedHeader));
        memcpy(h + 1, file.data() + stride, sizeof(MappedHeader));
        slot = b && (!a || h[1].generation > h[0].generation) ? 1 : 0;
        generation = h[slot].generation;
    }
    const T &get() const {
        return *reinterpret_cast<const T *>(file.data() + slot * stride + sizeof(MappedHeader));
    }
    void save(const T &next) {
        if (failed)
            throw Error("Clarity mapped checkpoint needs reopening after a write failure");
        if (generation == std::numeric_limits<uint64_t>::max())
            throw Error("Clarity checkpoint generation exhausted");
        const int target = 1 - slot;
        auto *p = file.data() + target * stride;
        MappedHeader h{};
        memcpy(h.magic, magic.data(), 8);
        h.version = 1;
        h.bodySize = sizeof(T);
        h.generation = generation + 1;
        h.checksum = mappedChecksum(&next, sizeof(T),mappedChecksum(&h,sizeof h));
        memcpy(p + sizeof h, &next, sizeof(T));
        memcpy(p, &h, sizeof h);
        try { file.sync(target * stride, target * stride + sizeof h + sizeof(T)); }
        catch (...) { failed = true; throw; }
        generation = h.generation;
        slot = target;
    }
};
struct alignas(8) MappedString { uint64_t offset; uint32_t length, reserved; };
static_assert(sizeof(MappedString) == 16);
// Native records/strings are appended. Index nodes may be updated in place
// after the outbox has durably published their redo transaction.
class MappedArena {
    MappedFile file;
  public:
    explicit MappedArena(const std::string &path):file(path) {}
    void checkEnd(uint64_t end) const {
        if (end < 8 || end > file.size()) throw Error("Clarity mapped data is truncated");
    }
    uint64_t append(uint64_t &end, const void *data, size_t size) {
        const uint64_t begin = (end + 7) & ~uint64_t(7);
        if (begin > 1024ULL * 1024 * 1024 || size > 1024ULL * 1024 * 1024 - begin)
            throw Error("Clarity mapped data exceeds its size limit");
        file.ensure(begin + size);
        if (size) memcpy(file.data() + begin, data, size);
        end = begin + size;
        return begin;
    }
    template<class T> uint64_t put(uint64_t &end, const T &v) {
        static_assert(std::is_trivially_copyable_v<T> && alignof(T) <= 8);
        return append(end, &v, sizeof v);
    }
    MappedString put(uint64_t &end, std::string_view text) {
        if (text.size() > 65536) throw Error("Clarity mapped string exceeds its size limit");
        return {append(end, text.data(), text.size()), uint32_t(text.size()), 0};
    }
    const void *at(uint64_t offset, size_t length, uint64_t end) const {
        checkEnd(end);
        if (offset < 8 || offset > end || length > end - offset)
            throw Error("Invalid Clarity mapped data offset");
        return file.data() + offset;
    }
    template<class T> const T &get(uint64_t offset, uint64_t end) const {
        if (offset % alignof(T)) throw Error("Unaligned Clarity mapped record");
        return *static_cast<const T *>(at(offset, sizeof(T), end));
    }
    std::string_view get(MappedString value, uint64_t end) const {
        return {static_cast<const char *>(at(value.offset, value.length, end)), value.length};
    }
    template<class T> void write(uint64_t offset, const T &value, uint64_t end) {
        static_assert(std::is_trivially_copyable_v<T>);
        (void)at(offset, sizeof(T), end);
        memcpy(file.data() + offset, &value, sizeof(T));
    }
    void sync(uint64_t from, uint64_t end) { file.sync(from, end); }
    void syncRanges(std::span<const std::pair<size_t, size_t>> ranges) { file.syncRanges(ranges); }
};
} // namespace clarity
