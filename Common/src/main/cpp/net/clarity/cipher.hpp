// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "bytes.hpp"
namespace clarity {
// Caller supplies complete blocks; SignedMessage handles its own padding.
Bytes aes256cbc(std::span<const uint8_t> key, const Bytes &iv,
                const Bytes &input, bool encrypt);
} // namespace clarity
