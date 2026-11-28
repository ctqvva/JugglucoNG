// SPDX-License-Identifier: GPL-3.0-or-later
#include "logging.hpp"
#include "bytes.hpp"
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <system_error>
#ifdef CLARITY_JUGGLUCO_LOG
#include "share/logs.hpp"
#endif
namespace clarity {
void diagnostic(const char *format, ...) noexcept {
    const int saved = errno;
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
#ifdef CLARITY_JUGGLUCO_LOG
    LOGGER("Clarity: %s\n", message);
#else
    fprintf(stderr, "Clarity: %s\n", message);
#endif
    errno = saved;
}
static const char *filename(const char *path) {
    const auto *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}
Error::Error(const std::string &message, const std::source_location &where)
    : std::runtime_error(message) {
    diagnostic("error %s:%u: %s", filename(where.file_name()), where.line(), what());
}
void caught(const char *operation, const std::source_location &where) noexcept {
    try {
        throw;
    } catch (const Error &e) {
        diagnostic("%s caught at %s:%u: %s", operation, filename(where.file_name()), where.line(),
                   e.what());
    } catch (const Json::exception &e) {
        diagnostic("%s caught at %s:%u: JSON exception id=%d (contents omitted)", operation,
                   filename(where.file_name()), where.line(), e.id);
    } catch (const std::system_error &e) {
        diagnostic("%s caught at %s:%u: system_error category=%s code=%d", operation,
                   filename(where.file_name()), where.line(), e.code().category().name(),
                   e.code().value());
    } catch (const std::bad_alloc &) {
        diagnostic("%s caught at %s:%u: allocation failed", operation, filename(where.file_name()),
                   where.line());
    } catch (const std::out_of_range &) {
        diagnostic("%s caught at %s:%u: out_of_range (contents omitted)", operation,
                   filename(where.file_name()), where.line());
    } catch (const std::exception &) {
        diagnostic("%s caught at %s:%u: std::exception (contents omitted)", operation,
                   filename(where.file_name()), where.line());
    } catch (...) {
        diagnostic("%s caught at %s:%u: non-standard exception", operation,
                   filename(where.file_name()), where.line());
    }
}
} // namespace clarity
