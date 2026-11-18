// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "bytes.hpp"
#include <memory>
namespace clarity {
// JSON exists only at the auth/protocol boundary. This adapter copies named
// fields to/from a fixed native mmap layout; it never dumps/parses local JSON.
class AccountStore {
    struct Impl;
    std::unique_ptr<Impl> impl;
  public:
    explicit AccountStore(const std::string &path);
    ~AccountStore();
    Json load() const;
    void save(const Json &config);
    void clear();
};
} // namespace clarity
