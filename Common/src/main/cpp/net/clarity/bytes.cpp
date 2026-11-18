// SPDX-License-Identifier: GPL-3.0-or-later
#include "bytes.hpp"
#include <algorithm>
#include <array>
#include <bit>
#include <cerrno>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>
namespace clarity {
static void fileError(const char *operation) {
    diagnostic("error: %s: errno=%d (%s)", operation, errno, strerror(errno));
}
struct FileDescriptor {
    int fd;
    ~FileDescriptor() { close(); }
    int close() {
        if (fd < 0)
            return 0;
        int previous = fd;
        fd = -1;
        int result = ::close(previous);
        if (result)
            fileError("close Clarity file");
        return result;
    }
};
static void removeTemporary(const std::string &path) {
    if (unlink(path.c_str()) && errno != ENOENT)
        fileError("remove temporary Clarity file");
}
Bytes randomBytes(size_t n) {
    Bytes out(n);
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fileError("open random source");
        throw Error("Random source unavailable");
    }
    FileDescriptor file{fd};
    size_t at = 0;
    while (at < n) {
        auto r = read(fd, out.data() + at, n - at);
        if (r < 0 && errno == EINTR)
            continue;
        if (r <= 0) {
            if (r < 0)
                fileError("read random source");
            throw Error("Random source failed");
        }
        at += r;
    }
    return out;
}
Bytes sha256(std::span<const uint8_t> input) {
    static constexpr uint32_t k[] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2};
    uint32_t h[] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    Bytes b(input.begin(), input.end());
    const uint64_t bits = uint64_t(b.size()) * 8;
    b.push_back(0x80);
    while (b.size() % 64 != 56)
        b.push_back(0);
    for (int i = 7; i >= 0; --i)
        b.push_back(bits >> (8 * i));
    for (size_t p = 0; p < b.size(); p += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i)
            w[i] = (uint32_t(b[p + 4 * i]) << 24) | (uint32_t(b[p + 4 * i + 1]) << 16) |
                   (uint32_t(b[p + 4 * i + 2]) << 8) | b[p + 4 * i + 3];
        for (int i = 16; i < 64; ++i) {
            auto a = w[i - 15], z = w[i - 2];
            w[i] = w[i - 16] + (std::rotr(a, 7) ^ std::rotr(a, 18) ^ (a >> 3)) + w[i - 7] +
                   (std::rotr(z, 17) ^ std::rotr(z, 19) ^ (z >> 10));
        }
        auto a = h[0], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], z = h[7], v = h[1];
        for (int i = 0; i < 64; ++i) {
            auto t1 = z + (std::rotr(e, 6) ^ std::rotr(e, 11) ^ std::rotr(e, 25)) +
                      ((e & f) ^ (~e & g)) + k[i] + w[i];
            auto t2 = (std::rotr(a, 2) ^ std::rotr(a, 13) ^ std::rotr(a, 22)) +
                      ((a & v) ^ (a & c) ^ (v & c));
            z = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = v;
            v = a;
            a = t1 + t2;
        }
        h[0] += a;
        h[1] += v;
        h[2] += c;
        h[3] += d;
        h[4] += e;
        h[5] += f;
        h[6] += g;
        h[7] += z;
    }
    Bytes out;
    for (auto v : h)
        for (int i = 3; i >= 0; --i)
            out.push_back(v >> (8 * i));
    return out;
}
Bytes hmac256(std::span<const uint8_t> key, std::span<const uint8_t> input) {
    Bytes k(key.begin(), key.end());
    if (k.size() > 64)
        k = sha256(k);
    k.resize(64);
    Bytes a(64), b(64);
    for (int i = 0; i < 64; ++i) {
        a[i] = k[i] ^ 0x36;
        b[i] = k[i] ^ 0x5c;
    }
    a.insert(a.end(), input.begin(), input.end());
    auto h = sha256(a);
    b.insert(b.end(), h.begin(), h.end());
    return sha256(b);
}
std::string hex(std::span<const uint8_t> b) {
    std::string s;
    for (auto c : b) {
        s += "0123456789abcdef"[c >> 4];
        s += "0123456789abcdef"[c & 15];
    }
    return s;
}
Bytes unhex(std::string_view s) {
    if (s.size() % 2)
        throw Error("Invalid hex");
    Bytes b;
    auto digit = [](char c) -> int {
        if (c >= '0' && c <= '9')
            return c - '0';
        if (c >= 'a' && c <= 'f')
            return c - 'a' + 10;
        if (c >= 'A' && c <= 'F')
            return c - 'A' + 10;
        throw Error("Invalid hex");
    };
    for (size_t i = 0; i < s.size(); i += 2)
        b.push_back((digit(s[i]) << 4) | digit(s[i + 1]));
    return b;
}
std::string base64(std::span<const uint8_t> b, bool url) {
    const char *table = url ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                            : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string s;
    uint32_t val = 0;
    int bits = 0;
    for (auto c : b) {
        val = (val << 8) | c;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            s += table[(val >> bits) & 63];
        }
    }
    if (bits)
        s += table[(val << (6 - bits)) & 63];
    if (!url)
        while (s.size() % 4)
            s += '=';
    return s;
}
Bytes unbase64(std::string_view s, bool url) {
    const std::string_view table =
        url ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    Bytes b;
    uint32_t val = 0;
    int bits = 0;
    size_t end = s.find('=');
    if (end == s.npos)
        end = s.size();
    if (s.size() - end > 2 || end % 4 == 1 || (url && end != s.size()))
        throw Error("Invalid base64");
    for (size_t i = end; i < s.size(); ++i)
        if (s[i] != '=')
            throw Error("Invalid base64");
    for (size_t i = 0; i < end; ++i) {
        auto n = table.find(s[i]);
        if (n == table.npos)
            throw Error("Invalid base64");
        val = (val << 6) | n;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            b.push_back(val >> bits);
        }
    }
    if (bits && (val & ((1u << bits) - 1)))
        throw Error("Invalid base64 padding");
    return b;
}
static std::string formatUuid(Bytes b) {
    b.resize(16);
    b[6] = (b[6] & 15) | 0x40;
    b[8] = (b[8] & 63) | 0x80;
    auto s = hex(b);
    return s.substr(0, 8) + "-" + s.substr(8, 4) + "-" + s.substr(12, 4) + "-" + s.substr(16, 4) +
           "-" + s.substr(20);
}
std::string uuid() { return formatUuid(randomBytes(16)); }
std::string stableUuid(std::string_view name) { return formatUuid(sha256(bytes(name))); }
bool isUuid(std::string_view s) {
    if (s.size() != 36)
        return false;
    for (size_t i = 0; i < s.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (s[i] != '-')
                return false;
        } else if (!((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f')))
            return false;
    }
    return true;
}
std::string timestamp(int64_t seconds, bool local, bool seven) {
    time_t t = seconds;
    tm v{};
    if (local)
        localtime_r(&t, &v);
    else
        gmtime_r(&t, &v);
    char s[64];
    strftime(s, sizeof(s), "%Y-%m-%dT%H:%M:%S", &v);
    std::string out = s;
    out += seven ? ".0000000" : ".000";
    if (!local)
        return out + "Z";
    char z[16];
    strftime(z, sizeof(z), "%z", &v);
    std::string zone = z;
    if (zone.size() != 5)
        throw Error("Invalid time zone");
    return out + zone.substr(0, 3) + ":" + zone.substr(3);
}
Bytes gzip(std::span<const uint8_t> input) {
    z_stream z{};
    if (deflateInit2(&z, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY) != Z_OK)
        throw Error("gzip initialization failed");
    Bytes out(deflateBound(&z, input.size()));
    z.next_in = const_cast<Bytef *>(input.data());
    z.avail_in = input.size();
    z.next_out = out.data();
    z.avail_out = out.size();
    int r = deflate(&z, Z_FINISH);
    out.resize(z.total_out);
    deflateEnd(&z);
    if (r != Z_STREAM_END)
        throw Error("gzip failed");
    return out;
}
Bytes gunzip(std::span<const uint8_t> input) {
    z_stream z{};
    if (inflateInit2(&z, 31) != Z_OK)
        throw Error("gzip initialization failed");
    z.next_in = const_cast<Bytef *>(input.data());
    z.avail_in = input.size();
    Bytes out;
    int r;
    do {
        std::array<uint8_t, 16384> buf{};
        z.next_out = buf.data();
        z.avail_out = buf.size();
        r = inflate(&z, Z_NO_FLUSH);
        out.insert(out.end(), buf.begin(), buf.end() - z.avail_out);
        if (out.size() > 16 * 1024 * 1024) {
            inflateEnd(&z);
            throw Error("Response too large");
        }
    } while (r == Z_OK);
    auto remaining = z.avail_in;
    inflateEnd(&z);
    if (r != Z_STREAM_END || remaining)
        throw Error("Invalid gzip");
    return out;
}
std::string readFile(const std::string &path, size_t limit) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        fileError("open Clarity file");
        throw Error("Cannot read Clarity file");
    }
    FileDescriptor file{fd};
    std::string out;
    char buf[16384];
    for (;;) {
        auto n = read(fd, buf, sizeof(buf));
        if (n < 0 && errno == EINTR)
            continue;
        if (n < 0 || out.size() + std::max<ssize_t>(n, 0) > limit) {
            if (n < 0)
                fileError("read Clarity file");
            throw Error("Invalid Clarity file");
        }
        if (!n)
            break;
        out.append(buf, n);
    }
    return out;
}
void atomicFile(const std::string &path, std::string_view contents) {
    std::string tmp = path + ".tmp-" + hex(randomBytes(8));
    int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) {
        fileError("create temporary Clarity file");
        throw Error("Cannot save Clarity state");
    }
    FileDescriptor file{fd};
    size_t at = 0;
    while (at < contents.size()) {
        auto n = write(fd, contents.data() + at, contents.size() - at);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            if (n < 0)
                fileError("write Clarity file");
            file.close();
            removeTemporary(tmp);
            throw Error("Cannot save Clarity state");
        }
        at += n;
    }
    int syncError = fsync(fd);
    if (syncError)
        fileError("fsync Clarity file");
    int closeError = file.close();
    if (syncError || closeError) {
        removeTemporary(tmp);
        throw Error("Cannot commit Clarity state");
    }
    if (rename(tmp.c_str(), path.c_str())) {
        fileError("rename Clarity file");
        removeTemporary(tmp);
        throw Error("Cannot commit Clarity state");
    }
    auto slash = path.rfind('/');
    std::string dir = slash == std::string::npos ? "." : path.substr(0, slash);
    int d = open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (d < 0) {
        fileError("open Clarity directory for sync");
        throw Error("Cannot sync Clarity directory");
    }
    FileDescriptor directory{d};
    if (fsync(d)) {
        fileError("fsync Clarity directory");
        throw Error("Cannot sync Clarity state");
    }
}
} // namespace clarity
