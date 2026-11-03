// SPDX-License-Identifier: GPL-3.0-or-later
// Allows platform-independent record/queue/protocol tests on non-ARM64 hosts.
#include "engine.hpp"
namespace clarity {
struct Engine::Impl {};
Engine::Engine(const std::string &) { throw Error("The native Clarity provider requires ARM64"); }
Engine::~Engine() = default;
Bytes Engine::invoke(const std::string &, const std::vector<Arg> &) {
    throw Error("The native Clarity provider requires ARM64");
}
std::string Engine::stringCall(const std::string &) {
    throw Error("The native Clarity provider requires ARM64");
}
bool Engine::verify(const std::string &, const std::vector<Arg> &) {
    throw Error("The native Clarity provider requires ARM64");
}
} // namespace clarity
