// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <source_location>
namespace clarity {
void diagnostic(const char *format, ...) noexcept __attribute__((format(printf, 1, 2)));
// Call only inside a catch. JSON/third-party exception messages can contain
// tokens or record contents, so log their type/id rather than what().
void caught(const char *operation,
            const std::source_location &where = std::source_location::current()) noexcept;
} // namespace clarity
