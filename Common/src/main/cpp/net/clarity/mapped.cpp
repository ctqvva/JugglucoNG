// SPDX-License-Identifier: GPL-3.0-or-later
#include "mapped.hpp"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>
namespace clarity {
namespace {
[[noreturn]] void mappingError(const char *operation) {
    const int code = errno;
    diagnostic("error: Clarity mapped storage %s: errno=%d (%s)", operation, code, strerror(code));
    throw Error(std::string("Clarity mapped storage ") + operation + " failed");
}
}
// Maps through the descriptor it already holds. Juggluco's Mmap reopens by
// path and logs that path on failure, and an outbox path contains the Dexcom
// account ID (its folder name). Errors here report the operation and errno only.
struct MappedFile::Impl {
    int fd = -1;
    bool created = false;
    size_t opened = 0; // on-disk size before this mapping grew the file
    unsigned char *memory = nullptr;
    size_t length = 0;
    void unmap() {
        if (memory && munmap(memory, length)) diagnostic("error: unmap Clarity mapped file: errno=%d", errno);
        memory = nullptr;
        length = 0;
    }
    // Grows the file first, so every mapped byte is backed by the file.
    void map(size_t size) {
        unmap();
        struct stat st{};
        if (fstat(fd, &st)) mappingError("stat");
        if (st.st_size < 0 || size_t(st.st_size) < size) {
            if (ftruncate(fd, off_t(size))) mappingError("extend");
        } else
            size = size_t(st.st_size);
        void *p = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (p == MAP_FAILED) mappingError("map");
        memory = static_cast<unsigned char *>(p);
        length = size;
    }
    ~Impl() { unmap(); if (fd >= 0 && close(fd)) diagnostic("error: close Clarity mapped file: errno=%d", errno); }
};
MappedFile::MappedFile(const std::string &path, bool exclusive):impl(std::make_unique<Impl>()) {
    impl->fd = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (impl->fd < 0) mappingError("open");
    if (exclusive && flock(impl->fd, LOCK_EX | LOCK_NB)) mappingError("lock (another uploader may be running)");
    struct stat st{};
    if (fstat(impl->fd, &st)) mappingError("stat");
    if (!S_ISREG(st.st_mode) || st.st_size < 0 || st.st_size > 1024LL * 1024 * 1024)
        throw Error("Invalid Clarity mapped file size or type");
    impl->created = !st.st_size;
    impl->opened = size_t(st.st_size);
    impl->map(std::max<size_t>(4096, size_t(st.st_size)));
    if (impl->created) {
        if (fsync(impl->fd)) mappingError("sync new file");
        auto slash = path.find_last_of('/');
        auto parent = slash == path.npos ? "." : path.substr(0, slash);
        int directory = open(parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (directory < 0) mappingError("open directory");
        int result = fsync(directory), code = errno;
        if (close(directory)) diagnostic("error: close Clarity mapped directory: errno=%d", errno);
        if (result) { errno = code; mappingError("sync directory"); }
    }
}
MappedFile::~MappedFile() = default;
bool MappedFile::created() const { return impl->created; }
size_t MappedFile::openedSize() const { return impl->opened; }
size_t MappedFile::size() const { return impl->length; }
unsigned char *MappedFile::data() {
    if (!impl->memory) throw Error("Clarity file mapping is unavailable; reopen required");
    return impl->memory;
}
const unsigned char *MappedFile::data() const {
    if (!impl->memory) throw Error("Clarity file mapping is unavailable; reopen required");
    return impl->memory;
}
bool MappedFile::zero(size_t from, size_t to) const {
    if (from > to || to > size()) throw Error("Invalid Clarity mapped range");
    const auto *p = data();
    return std::all_of(p + from, p + to, [](unsigned char c) { return c == 0; });
}
void MappedFile::ensure(size_t n) {
    (void)data(); // A failed previous remap must not lead to a null-pointer write.
    if (n <= size()) return;
    if (n > 1024ULL * 1024 * 1024) throw Error("Clarity mapped file exceeds size limit");
    size_t capacity = std::max(n, std::min(size() * 2, size_t(1024) * 1024 * 1024));
    impl->map(capacity);
}
void MappedFile::sync(size_t from, size_t to) {
    if (from == to) return;
    if (!data() || from > to || to > size()) throw Error("Invalid Clarity mapped sync range");
    const auto page = sysconf(_SC_PAGESIZE);
    if (page <= 0) throw Error("Cannot determine Clarity mapped page size");
    from -= from % size_t(page);
    if (msync(data() + from, to - from, MS_SYNC)) mappingError("msync");
    // Also report allocation/writeback errors and persist any file extension.
    if (fdatasync(impl->fd)) mappingError("fdatasync");
}
void MappedFile::syncRanges(std::span<const std::pair<size_t, size_t>> ranges) {
    if (ranges.empty()) return;
    const auto page = sysconf(_SC_PAGESIZE);
    if (page <= 0) throw Error("Cannot determine Clarity mapped page size");
    // Callers supply offsets in ascending order. Merge page overlaps without
    // walking or flushing unrelated mapped pages between distant tree nodes.
    size_t begin = 0, end = 0;
    auto flush = [&] {
        if (end > begin && msync(data() + begin, end - begin, MS_SYNC)) mappingError("msync index pages");
    };
    for (const auto &[offset, length] : ranges) {
        if (offset > size() || length > size() - offset)
            throw Error("Invalid Clarity mapped index sync range");
        const auto first = offset - offset % size_t(page);
        const auto last = std::min(size(), size_t(((offset + length + page - 1) / page) * page));
        if (end && first < begin) throw Error("Unordered Clarity mapped sync ranges");
        if (first > end) { flush(); begin = first; end = last; }
        else end = std::max(end, last);
    }
    flush();
    if (fdatasync(impl->fd)) mappingError("fdatasync index pages");
}
uint32_t mappedChecksum(const void *data, size_t size, uint32_t seed) {
    return crc32(seed, static_cast<const Bytef *>(data), size);
}
} // namespace clarity
