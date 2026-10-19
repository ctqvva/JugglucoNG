#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace libre3nfc {
// NFC is authoritative for connection credentials. A record with readings must
// not silently become a different activation session.
struct NfcMetadata {
  uint32_t start;
  uint32_t pin;
  const char *address;
  uint16_t warmup;
  uint16_t wear;

  bool valid(uint32_t now) const {
    if (start < 1590000000U || start > now || !address ||
        std::strlen(address) != 17 || !warmup || warmup > 255 || wear <= warmup)
      return false;
    for (int i = 0; i < 17; ++i) {
      const char c = address[i];
      if (i % 3 == 2 ? c != ':' :
          !((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')))
        return false;
    }
    return true;
  }
};

enum class NfcRefresh { updated, historyConflict, storageConflict };

template <class Info>
NfcRefresh refreshNfcMetadata(Info &info, const NfcMetadata &nfc) {
  const bool hasHistory = info.pollcount || info.scancount || info.endhistory;
  const bool addressChanged = std::strcmp(info.deviceaddress, nfc.address) != 0;
  if (hasHistory && info.starttime != nfc.start && addressChanged &&
      info.deviceaddress[0])
    return NfcRefresh::historyConflict;

  if (!hasHistory && info.wearduration2 && nfc.wear > info.wearduration2)
    return NfcRefresh::storageConflict; // Existing mmap geometry must not grow here.

  if (!hasHistory) {
    if (info.starttime != nfc.start || addressChanged) {
      info.patchState = 0;
      info.streamingIsEnabled = 0;
      info.lastLifeCountReceived = 1;
      info.lastHistoricLifeCountReceivedPos = 0;
      info.lockcount = 0;
    }
    info.starttime = nfc.start;
    info.warmup2 = nfc.warmup;
    info.wearduration2 = nfc.wear;
  }
  std::memcpy(info.deviceaddress, nfc.address, 18);
  info.pin = nfc.pin; // Zero is a valid four-byte PIN, not "missing".
  info.haskAuth = false;
  return NfcRefresh::updated;
}

// Response prefix: success byte, one or more A5 bytes, then 00.
inline size_t nfcPayloadOffset(const uint8_t *data, size_t size,
                               size_t payloadSize) {
  if (size < 3 || data[0] != 0 || data[1] != 0xA5)
    return size;
  size_t pos = 2;
  while (pos < size && data[pos] == 0xA5)
    ++pos;
  if (pos == size || data[pos++] != 0 || size - pos < payloadSize)
    return size;
  return pos;
}
} // namespace libre3nfc
