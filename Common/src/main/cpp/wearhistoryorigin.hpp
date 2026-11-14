#pragma once

#include <cerrno>
#include <cstdio>
#include <string>
#include <string_view>

// Device-local provenance, deliberately outside info.dat/polls.dat: those files
// can arrive from a peer. This sidecar is not part of native sensor exchange.
// Access is serialized by SensorGlucoseData::pollWriteMutex().
class WearHistoryOrigin {
  enum State { Unread = -1, Unknown = 0, PeerOnly = 1, Local = 2, Unavailable = 3 };
  State state = Unread;

  static std::string path(std::string_view directory) {
    return std::string(directory) + "/wear-history-origin";
  }

  State load(std::string_view directory) {
    if (state != Unread)
      return state;
    auto *file = std::fopen(path(directory).c_str(), "rb");
    if (!file)
      return state = errno == ENOENT ? Unknown : Unavailable;
    const int value = std::fgetc(file);
    std::fclose(file);
    return state = value == PeerOnly ? PeerOnly : value == Local ? Local : Unavailable;
  }

  bool save(std::string_view directory, State next) {
    if (load(directory) == next)
      return true;
    auto *file = std::fopen(path(directory).c_str(), "wb");
    if (!file) {
      state = Unavailable;
      return false;
    }
    const bool written = std::fputc(next, file) != EOF;
    const bool closed = std::fclose(file) == 0;
    state = written && closed ? next : Unavailable;
    return state == next;
  }

public:
  bool protectsExisting(std::string_view directory, bool hasSamples) {
    const auto origin = load(directory);
    // An older build left no provenance. Never call populated unknown history
    // peer-only merely because today's GATT/ownership proof is absent.
    return origin == Local || origin == Unavailable ||
           (origin == Unknown && hasSamples);
  }

  bool markLocal(std::string_view directory) { return save(directory, Local); }
  bool markPeerOnly(std::string_view directory) {
    return load(directory) == PeerOnly ||
           (state == Unknown && save(directory, PeerOnly));
  }
};
