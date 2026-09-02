/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 12:35:35 CET 2023                                                 */


#include "datbackup.hpp"
#include "nums/numdata.hpp"
#include "net/Connect.hpp"
#include <limits>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

extern bool javaCloneRecoverySenderBridgeReady();
extern std::vector<uint8_t> javaProbeCloneRecoveryOutgoing(
    const char *iceLabel, int64_t connectionGeneration);
extern std::vector<uint8_t> javaStartCloneRecoveryOutgoing(
    const char *iceLabel, int64_t connectionGeneration, const char *modeWire,
    bool includeJournal);
extern std::vector<uint8_t> javaNextCloneRecoveryOutgoingAction(
    const char *iceLabel, int64_t connectionGeneration);
extern int javaReportCloneRecoveryOutgoingResult(
    const char *iceLabel, int64_t connectionGeneration, const uint8_t *result,
    size_t resultSize);
extern std::vector<uint8_t> javaCloneRecoveryOutgoingStatus(
    const char *iceLabel);
extern std::vector<uint8_t> javaCancelCloneRecoveryOutgoing(
    const char *iceLabel);
extern int javaResumeCloneRecoveryOutgoing(const char *iceLabel,
                                           int64_t connectionGeneration);

namespace {

constexpr uint8_t cloneRecoveryActionVersion = 1;
constexpr uint8_t cloneRecoveryResultVersion = 1;
constexpr size_t cloneRecoveryActionHeaderBytes = 20;
constexpr size_t cloneRecoveryResultHeaderBytes = 16;
constexpr size_t cloneRecoveryMaximumPathBytes = 256;
constexpr uint32_t cloneRecoveryMaximumReadBytes = 64U * 1024U;
constexpr uint32_t cloneRecoveryMaximumControlBytes = 256U * 1024U;
constexpr uint32_t cloneRecoveryMaximumChunkBytes = 256U * 1024U;
constexpr uint64_t cloneRecoveryMaximumPackageBytes = 512ULL * 1024ULL * 1024ULL;
constexpr std::string_view cloneRecoveryCapabilityPath =
    "mirror/backfill/capabilities-v1";
constexpr std::string_view cloneRecoveryJobsPrefix = "mirror/backfill/jobs/";
constexpr size_t cloneRecoveryJobIdLength = 32;

enum class CloneRecoveryActionKind : uint8_t {
  probeCapabilities = 1,
  putManifest = 2,
  putPackageChunk = 3,
  putCommit = 4,
  getStatus = 5,
  putCancel = 6,
};

enum class CloneRecoveryResultOutcome : uint8_t {
  ok = 1,
  notFound = 2,
  transportError = 3,
  rejected = 4,
};

struct CloneRecoveryActionView {
  CloneRecoveryActionKind kind{};
  uint64_t offset = 0;
  std::string_view path;
  const uint8_t *payload = nullptr;
  uint32_t payloadBytes = 0;
  uint32_t requestedBytes = 0;
};

struct CloneRecoveryHostBinding {
  int allindex = -1;
  int sendindex = -1;
  std::string iceLabel;
  Connect *connect = nullptr;
  crypt_t *crypt = nullptr;
  uint64_t connectionGeneration = 0;
};

struct CloneRecoveryCapabilityProgress {
  uint64_t generation = 0;
  uint64_t nextOffset = 0;
  bool confirmed = false;
};

std::mutex cloneRecoveryCapabilityMutex;
std::unordered_map<std::string, CloneRecoveryCapabilityProgress>
    cloneRecoveryCapabilities;
std::mutex cloneRecoveryResumeMutex;
std::unordered_map<std::string, uint64_t> cloneRecoveryResumedGenerations;

uint32_t readUint32LittleEndian(const uint8_t *bytes) {
  return static_cast<uint32_t>(bytes[0]) |
         (static_cast<uint32_t>(bytes[1]) << 8U) |
         (static_cast<uint32_t>(bytes[2]) << 16U) |
         (static_cast<uint32_t>(bytes[3]) << 24U);
}

uint64_t readUint64LittleEndian(const uint8_t *bytes) {
  uint64_t value = 0;
  for (unsigned index = 0; index < 8; ++index) {
    value |= static_cast<uint64_t>(bytes[index]) << (index * 8U);
  }
  return value;
}

void appendUint32LittleEndian(std::vector<uint8_t> &out, uint32_t value) {
  for (unsigned index = 0; index < 4; ++index) {
    out.push_back(static_cast<uint8_t>(value >> (index * 8U)));
  }
}

void appendUint64LittleEndian(std::vector<uint8_t> &out, uint64_t value) {
  for (unsigned index = 0; index < 8; ++index) {
    out.push_back(static_cast<uint8_t>(value >> (index * 8U)));
  }
}

bool isCloneRecoveryJobPath(std::string_view path, std::string_view filename) {
  const size_t expectedBytes = cloneRecoveryJobsPrefix.size() +
                               cloneRecoveryJobIdLength + 1U + filename.size();
  if (path.size() != expectedBytes ||
      path.substr(0, cloneRecoveryJobsPrefix.size()) !=
          cloneRecoveryJobsPrefix) {
    return false;
  }
  const std::string_view jobId =
      path.substr(cloneRecoveryJobsPrefix.size(), cloneRecoveryJobIdLength);
  for (const char value : jobId) {
    if (!((value >= '0' && value <= '9') ||
          (value >= 'a' && value <= 'f'))) {
      return false;
    }
  }
  const size_t slash = cloneRecoveryJobsPrefix.size() +
                       cloneRecoveryJobIdLength;
  return path[slash] == '/' && path.substr(slash + 1U) == filename;
}

bool validateCloneRecoveryActionPath(CloneRecoveryActionKind kind,
                                     std::string_view path) {
  switch (kind) {
  case CloneRecoveryActionKind::probeCapabilities:
    return path == cloneRecoveryCapabilityPath;
  case CloneRecoveryActionKind::putManifest:
    return isCloneRecoveryJobPath(path, "manifest.json");
  case CloneRecoveryActionKind::putPackageChunk:
    return isCloneRecoveryJobPath(path, "package.jsonl.gz");
  case CloneRecoveryActionKind::putCommit:
    return isCloneRecoveryJobPath(path, "commit.json");
  case CloneRecoveryActionKind::getStatus:
    return isCloneRecoveryJobPath(path, "status.json");
  case CloneRecoveryActionKind::putCancel:
    return isCloneRecoveryJobPath(path, "cancel.json");
  }
  return false;
}

bool parseCloneRecoveryAction(const std::vector<uint8_t> &raw,
                              CloneRecoveryActionView &out) {
  if (raw.size() < cloneRecoveryActionHeaderBytes ||
      raw.size() > cloneRecoveryActionHeaderBytes +
                       cloneRecoveryMaximumPathBytes +
                       cloneRecoveryMaximumChunkBytes ||
      raw[0] != cloneRecoveryActionVersion || raw[2] != 0 || raw[3] != 0) {
    return false;
  }
  const uint8_t kindCode = raw[1];
  if (kindCode < static_cast<uint8_t>(CloneRecoveryActionKind::probeCapabilities) ||
      kindCode > static_cast<uint8_t>(CloneRecoveryActionKind::putCancel)) {
    return false;
  }
  const uint64_t offset = readUint64LittleEndian(raw.data() + 4);
  const uint32_t pathBytes = readUint32LittleEndian(raw.data() + 12);
  const uint32_t payloadBytes = readUint32LittleEndian(raw.data() + 16);
  if (offset > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
      pathBytes == 0 || pathBytes > cloneRecoveryMaximumPathBytes ||
      payloadBytes > cloneRecoveryMaximumChunkBytes ||
      pathBytes > raw.size() - cloneRecoveryActionHeaderBytes ||
      payloadBytes > raw.size() - cloneRecoveryActionHeaderBytes - pathBytes ||
      cloneRecoveryActionHeaderBytes + static_cast<size_t>(pathBytes) +
              static_cast<size_t>(payloadBytes) !=
          raw.size()) {
    return false;
  }
  const char *pathStart = reinterpret_cast<const char *>(
      raw.data() + cloneRecoveryActionHeaderBytes);
  if (std::char_traits<char>::find(pathStart, pathBytes, '\0')) {
    return false;
  }
  const auto kind = static_cast<CloneRecoveryActionKind>(kindCode);
  const std::string_view path(pathStart, pathBytes);
  if (!validateCloneRecoveryActionPath(kind, path)) {
    return false;
  }
  const uint8_t *payload = raw.data() + cloneRecoveryActionHeaderBytes + pathBytes;
  uint32_t requestedBytes = 0;
  switch (kind) {
  case CloneRecoveryActionKind::probeCapabilities:
  case CloneRecoveryActionKind::getStatus:
    if (payloadBytes != sizeof(uint32_t)) {
      return false;
    }
    requestedBytes = readUint32LittleEndian(payload);
    if (requestedBytes == 0 || requestedBytes > cloneRecoveryMaximumReadBytes ||
        offset > cloneRecoveryMaximumControlBytes ||
        requestedBytes > cloneRecoveryMaximumControlBytes - offset) {
      return false;
    }
    break;
  case CloneRecoveryActionKind::putManifest:
  case CloneRecoveryActionKind::putCommit:
  case CloneRecoveryActionKind::putCancel:
    if (offset != 0 || payloadBytes == 0 ||
        payloadBytes > cloneRecoveryMaximumControlBytes) {
      return false;
    }
    break;
  case CloneRecoveryActionKind::putPackageChunk:
    if (payloadBytes == 0 || offset > cloneRecoveryMaximumPackageBytes ||
        payloadBytes > cloneRecoveryMaximumPackageBytes - offset) {
      return false;
    }
    break;
  }
  out = {.kind = kind,
         .offset = offset,
         .path = path,
         .payload = payload,
         .payloadBytes = payloadBytes,
         .requestedBytes = requestedBytes};
  return true;
}

std::vector<uint8_t>
encodeCloneRecoveryResult(CloneRecoveryResultOutcome outcome,
                          uint64_t connectionGeneration,
                          const uint8_t *payload = nullptr,
                          uint32_t payloadBytes = 0) {
  if (connectionGeneration >
          static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
      payloadBytes > cloneRecoveryMaximumReadBytes ||
      (payloadBytes > 0 && !payload) ||
      (outcome != CloneRecoveryResultOutcome::ok && payloadBytes != 0)) {
    outcome = CloneRecoveryResultOutcome::rejected;
    payload = nullptr;
    payloadBytes = 0;
  }
  std::vector<uint8_t> result;
  result.reserve(cloneRecoveryResultHeaderBytes + payloadBytes);
  result.push_back(cloneRecoveryResultVersion);
  result.push_back(static_cast<uint8_t>(outcome));
  result.push_back(0);
  result.push_back(0);
  appendUint64LittleEndian(result, connectionGeneration);
  appendUint32LittleEndian(result, payloadBytes);
  if (payloadBytes) {
    result.insert(result.end(), payload, payload + payloadBytes);
  }
  return result;
}

bool validCloneRecoveryIceLabel(std::string_view label) {
  if (label.empty() || label.size() > 256) {
    return false;
  }
  for (const unsigned char value : label) {
    if (value < 0x20 || value == 0x7f) {
      return false;
    }
  }
  return true;
}

bool bindCloneRecoveryHostByAllIndex(int allindex,
                                     CloneRecoveryHostBinding &binding,
                                     bool requireConnected) {
  if (!backup || allindex < 0 || allindex >= backup->gethostnr()) {
    return false;
  }
  passhost_t &host = backup->getupdatedata()->allhosts[allindex];
  const int sendindex = host.index;
  if (host.deactivated || host.wearos || !host.ICE || host.receivedatafrom() ||
      !host.haspass() || sendindex < 0 ||
      sendindex >= backup->getupdatedata()->sendnr ||
      sendindex >= static_cast<int>(backup->con_vars.size())) {
    return false;
  }
  updateone &sender = backup->getupdatedata()->tosend[sendindex];
  if (sender.allindex != allindex) {
    return false;
  }
  const std::string_view labelView = host.getICEname();
  if (!validCloneRecoveryIceLabel(labelView)) {
    return false;
  }
  Connect *connect = sender.getConnect();
  crypt_t *crypt = sender.getcrypt();
  if (!connect || connect->allindex != allindex || !crypt ||
      (requireConnected && !connect->isConnectedSender())) {
    return false;
  }
  const uint64_t generation = connect->senderConnectionGeneration();
  if (generation > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
    return false;
  }
  binding = {.allindex = allindex,
             .sendindex = sendindex,
             .iceLabel = std::string(labelView),
             .connect = connect,
             .crypt = crypt,
             .connectionGeneration = generation};
  return true;
}

bool bindCloneRecoveryHostBySendIndex(int sendindex,
                                      CloneRecoveryHostBinding &binding,
                                      bool requireConnected) {
  if (!backup || sendindex < 0 ||
      sendindex >= backup->getupdatedata()->sendnr) {
    return false;
  }
  const int allindex = backup->getupdatedata()->tosend[sendindex].allindex;
  return bindCloneRecoveryHostByAllIndex(allindex, binding, requireConnected) &&
         binding.sendindex == sendindex;
}

bool bindCloneRecoveryHostByAllIndexSafely(
    int allindex, CloneRecoveryHostBinding &binding, bool requireConnected) {
#ifndef TESTMENU
  const std::lock_guard<std::mutex> hostLock(change_host_mutex);
#endif
  return bindCloneRecoveryHostByAllIndex(allindex, binding, requireConnected);
}

bool bindCloneRecoveryHostBySendIndexSafely(
    int sendindex, CloneRecoveryHostBinding &binding, bool requireConnected) {
#ifndef TESTMENU
  const std::lock_guard<std::mutex> hostLock(change_host_mutex);
#endif
  return bindCloneRecoveryHostBySendIndex(sendindex, binding,
                                          requireConnected);
}

bool cloneRecoveryCapabilityConfirmed(const std::string &label,
                                      uint64_t generation) {
  const std::lock_guard<std::mutex> lock(cloneRecoveryCapabilityMutex);
  const auto found = cloneRecoveryCapabilities.find(label);
  return found != cloneRecoveryCapabilities.end() &&
         found->second.generation == generation && found->second.confirmed;
}

bool acceptCloneRecoveryCapabilityPage(const std::string &label,
                                       uint64_t generation, uint64_t offset,
                                       uint32_t requestedBytes,
                                       uint32_t receivedBytes) {
  const std::lock_guard<std::mutex> lock(cloneRecoveryCapabilityMutex);
  CloneRecoveryCapabilityProgress &progress = cloneRecoveryCapabilities[label];
  if (progress.generation != generation || offset == 0) {
    progress = {.generation = generation, .nextOffset = 0, .confirmed = false};
  }
  if (progress.generation != generation || progress.confirmed ||
      offset != progress.nextOffset || receivedBytes > requestedBytes) {
    progress.confirmed = false;
    return false;
  }
  progress.nextOffset += receivedBytes;
  if (receivedBytes < requestedBytes) {
    progress.confirmed = progress.nextOffset > 0;
  }
  return true;
}

void rejectCloneRecoveryCapability(const std::string &label,
                                   uint64_t generation) {
  const std::lock_guard<std::mutex> lock(cloneRecoveryCapabilityMutex);
  auto found = cloneRecoveryCapabilities.find(label);
  if (found != cloneRecoveryCapabilities.end() &&
      found->second.generation == generation) {
    cloneRecoveryCapabilities.erase(found);
  }
}

bool cloneRecoveryGenerationWasResumed(const std::string &label,
                                       uint64_t generation) {
  const std::lock_guard<std::mutex> lock(cloneRecoveryResumeMutex);
  const auto found = cloneRecoveryResumedGenerations.find(label);
  return found != cloneRecoveryResumedGenerations.end() &&
         found->second == generation;
}

void markCloneRecoveryGenerationResumed(const std::string &label,
                                        uint64_t generation) {
  const std::lock_guard<std::mutex> lock(cloneRecoveryResumeMutex);
  cloneRecoveryResumedGenerations[label] = generation;
}

CloneRecoveryResultOutcome readCloneRecoveryVirtualFile(
    const CloneRecoveryHostBinding &binding,
    const CloneRecoveryActionView &action, std::vector<uint8_t> &payload) {
  if (action.offset > std::numeric_limits<uint32_t>::max()) {
    return CloneRecoveryResultOutcome::rejected;
  }
  const size_t pathBytes = action.path.size() + 1U;
  if (pathBytes > std::numeric_limits<uint16_t>::max()) {
    return CloneRecoveryResultOutcome::rejected;
  }
  constexpr size_t alignment = alignof(askfile);
  const size_t commandBytes =
      ((sizeof(askfile) + pathBytes + alignment - 1U) / alignment) * alignment;
  if (commandBytes > static_cast<size_t>(std::numeric_limits<int>::max())) {
    return CloneRecoveryResultOutcome::rejected;
  }
  std::vector<uint8_t> command(commandBytes, 0);
  askfile *ask = reinterpret_cast<askfile *>(command.data());
  ask->com = saskfile;
  ask->namelen = static_cast<uint16_t>(pathBytes);
  ask->off = static_cast<uint32_t>(action.offset);
  ask->len = action.requestedBytes;
  memcpy(ask->name, action.path.data(), action.path.size());
  ask->name[action.path.size()] = '\0';
  if (!binding.connect->s_noacksendcommand(
          binding.crypt, command.data(), static_cast<int>(commandBytes))) {
    return CloneRecoveryResultOutcome::transportError;
  }
  dataonlyptr response = binding.connect->receivedataonly_s(
      binding.crypt, static_cast<int>(action.requestedBytes));
  if (!response) {
    return CloneRecoveryResultOutcome::transportError;
  }
  if (response->len == -1) {
    return CloneRecoveryResultOutcome::notFound;
  }
  if (response->len < 0 ||
      static_cast<uint32_t>(response->len) > action.requestedBytes) {
    return CloneRecoveryResultOutcome::rejected;
  }
  payload.assign(response->data, response->data + response->len);
  return CloneRecoveryResultOutcome::ok;
}

std::string recoveryBytesToString(const std::vector<uint8_t> &bytes) {
  if (bytes.empty()) {
    return {};
  }
  return std::string(reinterpret_cast<const char *>(bytes.data()), bytes.size());
}

void wakeCloneRecoverySender(int sendindex) {
  if (!backup || sendindex < 0 ||
      sendindex >= static_cast<int>(backup->con_vars.size())) {
    return;
  }
  if (Backup::condvar_t *condition = backup->con_vars[sendindex]) {
    condition->wakebackup(Backup::wakerecovery);
  }
}

bool wakeCloneRecoverySenderForLabel(std::string_view iceLabel) {
  if (!validCloneRecoveryIceLabel(iceLabel)) {
    return false;
  }
#ifndef TESTMENU
  /* Host edits take this mutex before replacing indices, connections or
     sender condition variables. Keep it only through lookup and wake: the
     Java callback and all transport I/O happen after it is released. */
  const std::lock_guard<std::mutex> hostLock(change_host_mutex);
#endif
  if (!backup) {
    return false;
  }
  const int hostCount = backup->gethostnr();
  for (int allindex = 0; allindex < hostCount; ++allindex) {
    CloneRecoveryHostBinding binding;
    if (bindCloneRecoveryHostByAllIndex(allindex, binding, false) &&
        binding.iceLabel == iceLabel) {
      wakeCloneRecoverySender(binding.sendindex);
      return true;
    }
  }
  return false;
}

} // namespace
void receivetimeout(int sock,int secs) ;
void sendtimeout(int sock,int secs) ;
extern bool sendResetDevices(crypt_t *pass,const int sock) ;

extern void stopreceiver() ;
//constexpr const char orsettings[]="orsettings.dat";

extern std::vector<Numdata*> numdatas;
 void uptodate(int ind) {
     LOGGER("uptodate %d\n",ind);
    lastuptodate[ind]=time(nullptr);
    #ifdef WEAROS
    bool rmInitLayout();
        static bool did=rmInitLayout();
    #endif
    }

void uptodate(passhost_t *host) {
    if(backup)
        uptodate(host-backup->getupdatedata()->allhosts);
    }


static uint32_t connectTimes[maxallhosts] = {};
void setConnectTime(const int allindex,uint32_t tim) {
   if (allindex>=0 && allindex<maxallhosts)
       connectTimes[allindex]=tim;
   }
void setConnectTime(const passhost_t *host,uint32_t tim) {
   setConnectTime(host-backup->getupdatedata()->allhosts,tim);
   };
uint32_t getConnectTime(const int allindex) {
   if (allindex<0 || allindex>=maxallhosts) return 0;
   return connectTimes[allindex];
   }
uint32_t getConnectTime(const passhost_t *host) {
   return getConnectTime(host-backup->getupdatedata()->allhosts);
   };

int processCloneRecoveryAction(int sendindex) {
  if (!javaCloneRecoverySenderBridgeReady()) {
    return 0;
  }
  CloneRecoveryHostBinding binding;
  if (!bindCloneRecoveryHostBySendIndexSafely(sendindex, binding, true)) {
    return 0;
  }
  if (!cloneRecoveryGenerationWasResumed(binding.iceLabel,
                                         binding.connectionGeneration)) {
    const int directive = javaResumeCloneRecoveryOutgoing(
        binding.iceLabel.c_str(),
        static_cast<int64_t>(binding.connectionGeneration));
    if (directive >= 0) {
      markCloneRecoveryGenerationResumed(binding.iceLabel,
                                         binding.connectionGeneration);
    }
    if (directive != 1) {
      return 0;
    }
  }
  const std::vector<uint8_t> rawAction =
      javaNextCloneRecoveryOutgoingAction(
          binding.iceLabel.c_str(),
          static_cast<int64_t>(binding.connectionGeneration));
  if (rawAction.empty()) {
    return 0;
  }

  CloneRecoveryActionView action;
  CloneRecoveryResultOutcome outcome = CloneRecoveryResultOutcome::rejected;
  std::vector<uint8_t> resultPayload;
  if (parseCloneRecoveryAction(rawAction, action)) {
    /* The binding can change while Java chooses the action. Never put a frame
       on a replacement ICE agent or a host that has been edited meanwhile.
       Host mutation can delete or shift the raw Connect/crypt objects, so the
       existing host mutex must also pin them for the bounded transport call. */
#ifndef TESTMENU
    const std::lock_guard<std::mutex> transportLifetimeLock(change_host_mutex);
#endif
    CloneRecoveryHostBinding current;
    if (!bindCloneRecoveryHostBySendIndex(sendindex, current, true) ||
        current.allindex != binding.allindex ||
        current.iceLabel != binding.iceLabel ||
        current.connect != binding.connect ||
        current.crypt != binding.crypt ||
        current.connectionGeneration != binding.connectionGeneration) {
      outcome = CloneRecoveryResultOutcome::transportError;
    } else if (action.kind == CloneRecoveryActionKind::probeCapabilities ||
               action.kind == CloneRecoveryActionKind::getStatus) {
      outcome = readCloneRecoveryVirtualFile(current, action, resultPayload);
      if (action.kind == CloneRecoveryActionKind::probeCapabilities) {
        if (outcome != CloneRecoveryResultOutcome::ok ||
            !acceptCloneRecoveryCapabilityPage(
                current.iceLabel, current.connectionGeneration, action.offset,
                action.requestedBytes,
                static_cast<uint32_t>(resultPayload.size()))) {
          rejectCloneRecoveryCapability(current.iceLabel,
                                        current.connectionGeneration);
          if (outcome == CloneRecoveryResultOutcome::ok) {
            outcome = CloneRecoveryResultOutcome::rejected;
            resultPayload.clear();
          }
        }
      }
    } else if (!cloneRecoveryCapabilityConfirmed(
                   current.iceLabel, current.connectionGeneration)) {
      outcome = CloneRecoveryResultOutcome::rejected;
    } else {
      const bool sent = current.connect->senddata(
          current.crypt, static_cast<int>(action.offset), action.payload,
          static_cast<int>(action.payloadBytes), action.path);
      outcome = sent ? CloneRecoveryResultOutcome::ok
                     : CloneRecoveryResultOutcome::transportError;
    }
  }

  const std::vector<uint8_t> result = encodeCloneRecoveryResult(
      outcome, binding.connectionGeneration,
      resultPayload.empty() ? nullptr : resultPayload.data(),
      static_cast<uint32_t>(resultPayload.size()));
  return javaReportCloneRecoveryOutgoingResult(
      binding.iceLabel.c_str(),
      static_cast<int64_t>(binding.connectionGeneration), result.data(),
      result.size());
}

int resumeCloneRecoveryForHost(int allindex, int sendindex) {
  if (!javaCloneRecoverySenderBridgeReady()) {
    return 0;
  }
  CloneRecoveryHostBinding binding;
  if (!bindCloneRecoveryHostByAllIndexSafely(allindex, binding, false) ||
      binding.sendindex != sendindex) {
    return 0;
  }
  const int directive = javaResumeCloneRecoveryOutgoing(
      binding.iceLabel.c_str(),
      static_cast<int64_t>(binding.connectionGeneration));
  if (directive >= 0) {
    markCloneRecoveryGenerationResumed(binding.iceLabel,
                                       binding.connectionGeneration);
  }
  return directive;
}

std::string probeCloneRecoveryForHost(int allindex) {
  if (!javaCloneRecoverySenderBridgeReady()) {
    return {};
  }
  CloneRecoveryHostBinding binding;
  if (!bindCloneRecoveryHostByAllIndexSafely(allindex, binding, false)) {
    return {};
  }
  const std::vector<uint8_t> status = javaProbeCloneRecoveryOutgoing(
      binding.iceLabel.c_str(),
      static_cast<int64_t>(binding.connectionGeneration));
  if (!status.empty()) {
    wakeCloneRecoverySenderForLabel(binding.iceLabel);
  }
  return recoveryBytesToString(status);
}

std::string startCloneRecoveryForHost(int allindex, std::string_view modeWire,
                                      bool includeJournal) {
  if (!javaCloneRecoverySenderBridgeReady() || modeWire.empty() ||
      modeWire.size() > 64 ||
      std::char_traits<char>::find(modeWire.data(), modeWire.size(), '\0')) {
    return {};
  }
  CloneRecoveryHostBinding binding;
  if (!bindCloneRecoveryHostByAllIndexSafely(allindex, binding, false)) {
    return {};
  }
  const std::string mode(modeWire);
  const std::vector<uint8_t> status = javaStartCloneRecoveryOutgoing(
      binding.iceLabel.c_str(),
      static_cast<int64_t>(binding.connectionGeneration), mode.c_str(),
      includeJournal);
  if (!status.empty()) {
    wakeCloneRecoverySenderForLabel(binding.iceLabel);
  }
  return recoveryBytesToString(status);
}

std::string cancelCloneRecoveryForHost(int allindex) {
  if (!javaCloneRecoverySenderBridgeReady()) {
    return {};
  }
  CloneRecoveryHostBinding binding;
  if (!bindCloneRecoveryHostByAllIndexSafely(allindex, binding, false)) {
    return {};
  }
  const std::vector<uint8_t> status =
      javaCancelCloneRecoveryOutgoing(binding.iceLabel.c_str());
  if (!status.empty()) {
    wakeCloneRecoverySenderForLabel(binding.iceLabel);
  }
  return recoveryBytesToString(status);
}

std::string cloneRecoveryStatusForHost(int allindex) {
  if (!javaCloneRecoverySenderBridgeReady()) {
    return {};
  }
  CloneRecoveryHostBinding binding;
  if (!bindCloneRecoveryHostByAllIndexSafely(allindex, binding, false)) {
    return {};
  }
  return recoveryBytesToString(
      javaCloneRecoveryOutgoingStatus(binding.iceLabel.c_str()));
}

bool wakeCloneRecoveryForLabel(std::string_view iceLabel) {
  return wakeCloneRecoverySenderForLabel(iceLabel);
}
/*
void deactivateHost(int index,bool deactive) { 
    backup->deactivateHost(index,deactive);
    } */

int updateone::updateiob() {
    const auto iobupdate=settings->data()->iobupdate;
    if(iobupdate>iobupdated ) {
          crypt_t *pass=getcrypt();
           std::vector<subdata> vect;
           vect.reserve(1);
           const auto startinsulin=offsetof(Tings,insulintypes);
           const auto endinsulin=offsetof(Tings,iobupdate)+sizeof(Tings::iobupdate);
           LOGGER("updateiob start=%zd end=%zd\n",startinsulin,endinsulin);
           vect.push_back({reinterpret_cast<const senddata_t *>(settings->data()->insulintypes),startinsulin,endinsulin-startinsulin});
           auto *con0=getConnect();
           if(!con0||!con0->senddata(pass,vect,settingsdat) )
                    return 0;
            iobupdated=iobupdate;
            return 1;
            }
     return 2;
    }


int updateone::sendCalibrate() {
    auto *con=getConnect();
    if(!con) return 0;
    return sensors->sendCalibrates(getcrypt(), con,ind,startSendCalibrate);
    }

int updateone::sendCloneIobSnapshot() {
    const passhost_t &host = backup->getHosts()[allindex];
    if (!host.ICE)
        return 2;
    Connect *connect = getConnect();
    if (!connect)
        return 0;
    extern std::string javaExportCloneIobSnapshot();
    const std::string json = javaExportCloneIobSnapshot();
    if (json.empty())
        return 2;
    static constexpr std::string_view path = "mirror/iob.json";
    if (!connect->senddata(
            getcrypt(), 0,
            reinterpret_cast<const senddata_t *>(json.data()),
            static_cast<int>(json.size()), path)) {
        LOGGER("sendCloneIobSnapshot failed for host=%d\n", allindex);
        return 0;
    }
    return 1;
}

int updateone::sendCloneJournalSnapshot() {
    const passhost_t &host = backup->getHosts()[allindex];
    if (!host.ICE)
        return 2;
    Connect *connect = getConnect();
    if (!connect)
        return 0;
    extern std::string javaExportCloneJournalSnapshot();
    const std::string json = javaExportCloneJournalSnapshot();
    if (json.empty())
        return 2;

    // A journal snapshot is stable until a local treatment changes. Avoid
    // resending it with every glucose update, but resend after ICE creates a
    // new agent so a restarted receiver is backfilled immediately.
    static std::string lastPayload[maxallhosts];
    static uint64_t lastConnectionGeneration[maxallhosts] = {};
    const uint64_t connectionGeneration = connect->senderConnectionGeneration();
    if (allindex >= 0 && allindex < maxallhosts &&
        lastPayload[allindex] == json &&
        lastConnectionGeneration[allindex] == connectionGeneration) {
        return 2;
    }
    static constexpr std::string_view path = "mirror/journal.json";
    if (!connect->senddata(
            getcrypt(), 0,
            reinterpret_cast<const senddata_t *>(json.data()),
            static_cast<int>(json.size()), path)) {
        LOGGER("sendCloneJournalSnapshot failed for host=%d\n", allindex);
        return 0;
    }
    if (allindex >= 0 && allindex < maxallhosts) {
        lastPayload[allindex] = json;
        lastConnectionGeneration[allindex] = connectionGeneration;
    }
    return 1;
}


#ifndef WEAROS
int updateone::numbertypes() {
    if(!sendNight&&!sendLibre) {
        return 2;
        }
    const auto &host= backup->getHosts()[allindex]; 
    if(!host.wearos)  {
        sendLibre=false;
        sendNight=false;
        return 2;
        }
  std::vector<subdata> vect;
  vect.reserve(2);
  if(sendNight) {
       const auto startnight=offsetof(Tings,Nightnums);
       const auto endnight=offsetof(Tings,nightinterval);
       LOGGER("Nightnums start=%zd end=%zd\n",startnight,endnight);
       vect.push_back({reinterpret_cast<const senddata_t *>(settings->data()->Nightnums),startnight,endnight-startnight});
       }
  if(sendLibre) {
       const auto startnight=offsetof(Tings,librenums);
       const auto endnight=offsetof(Tings,libreaccountIDnum);
       LOGGER("librenums start=%zd end=%zd\n",startnight,endnight);
       vect.push_back({reinterpret_cast<const senddata_t *>(settings->data()->librenums),startnight,endnight-startnight});
       }
   auto *con1=getConnect();
   if(!con1||!con1->senddata(getcrypt(),vect,settingsdat) )
        return 0;
    sendLibre=false;
    sendNight=false;
    return 1;
  }
#endif

int updateone::update() {
    Connect *connect=getConnect();
    if(!connect || !connect->isConnectedSender())
        return 0;
    crypt_t *pass=getcrypt();

    bool sendsensors=(sendstream||sendscans) ;
    int ret =0;
    LOGGER("updateone::update starttime=%d\n",starttime);
#ifdef WEAROS
    if(blueWatch) {
        if(!connect->sendBlueWatch(pass,sendstream,sendnums)) 
            return 0;
         backup->getupdatedata()->allhosts[allindex].receivefrom=3;
         blueWatch=false;
        }
#endif
    if(starttime) {
        if(sendnums) {
            if(nums[0].lastlastpos==0&&nums[1].lastlastpos==0) {
                LOGAR("sendnums==true 2*lastlastpos==0");
                if(starttime==1)  {
                    for(auto el:numdatas)
                        if(! el->sendbackupinit(pass,connect,nums) )
                            return 0;
                    }
                else   {
                    bool numsbackupsendinit(crypt_t*pass,Connect *,struct changednums *nuall,uint32_t starttime) ;
                    if(!numsbackupsendinit(pass,connect,nums, starttime) )
                        return 0;
                    startSendCalibrate=sensors->firstafter(starttime);
                    const int last=sensors->last();
                    for(int it=startSendCalibrate;it<=last;++it) {
                        SensorGlucoseData *sens=sensors->getSensorData(it);
                        sens->updateCaliTime(ind,starttime);
                        }
                    }
                }
             else {
                LOGGER("sendnums==true nums[0].lastlastpos==%d nums[1].lastlastpos==%d\n", nums[0].lastlastpos,nums[1].lastlastpos);
                }
            }
        else {
            LOGAR("sendnums==false");
            }

        if(starttime!=1)  {
            if(sendsensors) {
                if( !sensors->setbackuptime(pass, connect,ind,starttime,starttimeindex))  {
                    LOGAR("updateone::update failed");
                    return 0;
                    }
                }
            else {
                LOGAR("sendsensor=false");
                }
            }
        LOGAR("updateone::update set starttime=0");
        starttime=0;
        ret=1;
       }

    int subdid=updatenums();
    if(!subdid)
        return 0;
    ret|=subdid;
    if(sendsensors) {
        subdid=sensors->update(pass,connect,ind,startsensors,firstsensor,sendstream,sendscans,restore,resetdevices);
        if(subdid&4) {
            resetdevices=true;
            subdid&=3;
            }
        if(!subdid)
            return 0; 
        ret|=subdid;
        }
    const auto update= settings->getupdate();
    static bool init=true;
    if(update>updatesettings||init) {
        init=false;
        if(sendnums) {
            std::vector<subdata> vect;
            vect.reserve(3);
            bool nochangenum =true;
            vect.push_back({reinterpret_cast<const senddata_t *>(&nochangenum),offsetof(Tings,nochangenum),sizeof(nochangenum)});
            constexpr const int  bloodvaroff=offsetof(Tings, bloodvar);
            vect.push_back({reinterpret_cast<const senddata_t *>( settings->data())+bloodvaroff,bloodvaroff,1});
            constexpr const int  sharedstart=offsetof(Tings, update);
            constexpr const int len=offsetof(Tings,mealvar )+1-sharedstart;
            vect.push_back({reinterpret_cast<const senddata_t *>( settings->data())+sharedstart,sharedstart,len});
            if(!connect->senddata(pass,vect,settingsdat) ) 
                return 0;
            }
        ret=1;
        }
    updatesettings=update;
    if(int iobret=updateiob()) {
        ret|=iobret;
        }
    else
        return 0;
    if(int cloneIobRet=sendCloneIobSnapshot()) {
        ret|=cloneIobRet;
        }
    else
        return 0;
    if(int cloneJournalRet=sendCloneJournalSnapshot()) {
        ret|=cloneJournalRet;
        }
    else
        return 0;
#ifndef WEAROS
    if(int numret=numbertypes())
        ret|=numret;
    else
        return 0;
#endif
    uptodate(allindex) ;
    if(!connect->noacksendone(pass, suptodate))
        return 0;
    if(resetdevices) {
        if(!connect->sendResetDevices(pass) ) {
            LOGGER("sendResetDevices(%p,%d) failed\n",pass,connect->getSenderIdent() );
            return 0;
            }
        ret=1;
        resetdevices=false;
        }
    return ret;
    }
    
extern int  updatenums(crypt_t *,Connect *,struct changednums *nums,int);

int     updateone::updatenums() {
    if(!sendnums)
        return 2;
    if(starttime) {
        return update();
        }
    Connect *connect=getConnect();
    if(!connect || !connect->isConnectedSender())
        return 0;
    if(!sendjugglucoid) {
        LOGAR("updatenums sendjugglucoid");
        const int offset=offsetof(Tings,jugglucoID);
        const auto *data=reinterpret_cast<const senddata_t*>(&settings->data()->jugglucoID);
        const int len=sizeof(Tings::jugglucoID);
        if(!connect->senddata(getcrypt(),offset,data,len,settingsdat) )  {
            LOGAR("updatenums sendjugglucoid error");
            return 0;
            }
        sendjugglucoid=true;
        }
    if(int did= ::updatenums(getcrypt(),connect,nums,ind))  {
        return sendCalibrate()|did;
        }
    return 0; 
    }

int  updateone::updatestreamu() {
    if(!sendstream)
        return 2;
    if(starttime) {
        return update();
        }
    Connect *connect=getConnect();
    if(!connect || !connect->isConnectedSender())
        return 0;
    return sensors->updatestreams(getcrypt(),connect,ind,firstsensor,sendscans?2:1);
     } 
int updateone::updatescansu() {
    if(!sendscans)
        return 2;
    if(starttime) {
        return update();
        }
    Connect *connect=getConnect();
    if(!connect || !connect->isConnectedSender())
        return 0;
    return sensors->updatescanss(getcrypt(),connect,ind,firstsensor,sendstream);
     } 
/* updateBeforeSwitch is an Air-sensor-specific feature not ported here */
void wakeupall(){
    if(backup) {
        LOGAR("wakeupall");
        backup->wakebackup(wakeall|wakereconnect);
        }
    }
void wakeupstream(){
    if(backup) {
        LOGAR("wakeupstream");
        backup->wakebackup(wakestream|wakereconnect);
        }
    }
bool networkpresent=false;


//int hostsocks[maxallhosts]{-1,-1,-1,-1,-1,-1,-1,-1};
uint32_t lastuptodate[maxallhosts]={};
//std::vector<int> sendsocks;
std::vector<crypt_t *> crypts;
Backup *backup=nullptr;
#define SENDPASSIVE 1
#define RECEIVEFROM 2
static int saysender(const passhost_t *host) {
    if(host->activereceive) 
        return RECEIVEFROM;
    return 0;
    }
#ifdef WEAROS_MESSAGES
extern bool wearmessages[];
extern int messagemakeconnection(passhost_t *pass,int &sock,crypt_t*ctx,char stype);
#endif
static int sayactivereceive(const passhost_t *host) {
    if(host->index>=0) {
        return SENDPASSIVE;
        }
    return 0;
    }

    crypt_t * updateone::getcrypt() const {
#if defined(WEAROS_MESSAGES)&&!defined(ENCRYPTMESSAGES)
        if(wearmessages[allindex]) {
            if(backup) {
                 const auto &host= backup->getHosts()[allindex]; //WHY???
                if(host.wearos)  {
                    LOGGER("getcrypt allindex=%d wearos\n",allindex);
                    return nullptr; //WHY
                    }
                }
            }
#endif 
        if(crypts.size()>ind&&ind>=0)
            return crypts[ind];
        return nullptr;
        }
void    updateone::open() {
     auto *host=backup->getupdatedata()->allhosts+allindex;
     LOGGER("updateone::open %d %s  receivefrom=%d sendpassive=%d activereceive=%d\n",allindex,host->getnameif(),host->receivefrom,host->sendpassive,host->activereceive);
     if(host->deactivated) {
         return;
          }

#ifdef WEAROS_MESSAGES
    if(host->wearos&&wearmessages[allindex]) {
         messagemakeconnection(host,getsock(),getcrypt(),saysender(host));
        
     }   else 
#endif
    {
        if(host->sendpassive) 
            return;
       auto *con=connections[allindex];
       if(con)
           con->makeconnection(host,getcrypt(),saysender(host));

    }
   ;
   if(auto *con=connections[allindex]) {
        con->setSenderTimeouts();
        LOGGER("updateone::open()=%d\n",connections[allindex]->getSenderIdent());
        }
   else {
      LOGGER("updateone::open() connections[%d]==null\n",allindex);
      }
    }




static void sendup(passhost_t *hostptr) {
    const bool haspas= hostptr->haspass();
    LOGGER("wake sender %spass\n",(haspas?"":"no" ));
    crypt_t ctx;
    crypt_t *ctxptr=haspas?&ctx:nullptr;

#ifndef HAVE_NOPRCTL
        prctl(PR_SET_NAME, "wake sender", 0, 0, 0);
#endif
     int allindex=hostptr-backup->getupdatedata()->allhosts;

     Connect *connect=connections[allindex];
     if(connect) {
        destruct _{[connect] {
           connect->senduprunning.clear();
           LOGGER("makeWakeThread: %d senduprunning.clear()\n",connect->allindex);
            }};
         connect->shutdownSender();
         connect->shutdownReceiver();
         sleep(1);
        if(connect->makeconnection(hostptr,ctxptr,saysender(hostptr))>=0) {
            if(!hostptr->ICE) {
                int sock=static_cast<TCPConnect*>(connect)->getSenderSock();
                sendtimeout(sock,60*5);
                receivetimeout(sock,0);
                }
            if(connect->sendbackup(ctxptr)) {
                connect=connections[allindex];
                LOGGER("sendup success %d\n",connect?connect->getSenderIdent():-1);    
                }
            else {
                connect=connections[allindex];
                LOGGER("%d: failure %d\n",agettid(),connect?connect->getSenderIdent():-1);    
                }
            if(!hostptr->ICE&&(connect=connections[allindex]))
                connect->closeSenderConnection();
            }
            }
    }

std::vector<Backup::condvar_t*> active_receive;

#include <chrono>
using namespace std::chrono_literals;
void activereceivethread(int allindex,passhost_t *pass) {
    auto &status=mirrorstatus[allindex].receive;
    status.activereceivethread=true;
    destruct _dest([&status](){ status.activereceivethread=false;});
    const int h = pass->activereceive - 1;
    if(h < 0) {
        LOGGER("activereceivethread h(%d)<0\n", h);
        return;
    }
    if(static_cast<size_t>(h) >= active_receive.size()) {
        LOGGER("activereceivethread h(%d)>=active_receive.size()(%zu)\n", h, active_receive.size());
        return;
    }
    if(!active_receive[h]) {
        LOGGER("activereceivethread !active_receive[%d]\n",h);
        return;
        }
    const bool haspas = pass->haspass();
    crypt_t ctx, *ctxptr = haspas ? &ctx : nullptr;
    decltype(active_receive[h]->dobackup) current{};
{
    constexpr const int maxbuf = 50;
    char buf[maxbuf];
#ifndef NOLOG
    int slen =
#endif
    snprintf(buf, maxbuf, "Ractive%d_%d", allindex,h);
    LOGGERN(buf, slen);
#ifndef HAVE_NOPRCTL
    prctl(PR_SET_NAME, buf, 0, 0, 0);
#endif
}
    while(true) {
          active_receive[h]->dobackup=active_receive[h]->dobackup&(~current);
          if(!active_receive[h]->dobackup) {
            std::unique_lock<std::mutex> lck(active_receive[h]->backupmutex);
            LOGAR("R-active before lock");
    constexpr const int waitsec=
#if defined(JUGGLUCO_APP) && !defined(WEAROS)
    70
#else
    30
#endif
;
#ifdef WEAROS_MESSAGES
    if(pass->wearos&&wearmessages[allindex]) {
        active_receive[h]->backupcond.wait(lck, [h] {return active_receive[h]->dobackup; });   
        LOGAR("R-active after wait");
        }
   else   
#endif  
        {
           LOGGER("activereceivethread before wait_for %d %p\n",h ,active_receive[h]);

#ifndef NOLOG
            auto status=
#endif
                active_receive[h]->backupcond.wait_for(lck,std::chrono::seconds(waitsec));    //In reality much longer if phone is in doze mode.
            LOGGER("R-active after lock %stimeout\n",(status==std::cv_status::no_timeout)?"no-":"");
            }
            }
       LOGGER("before if(!active_receive[%d]) %p \n",h,active_receive[h]);
       if(static_cast<size_t>(h)>=active_receive.size()||!active_receive[h]) {
            LOGGER("active_receive[%d]==0, return\n",h);
            return;
          }
       current=active_receive[h]->dobackup;
       LOGAR("after current=active_receive[h]->dobackup;");
       TCPConnect *con=static_cast<TCPConnect*>(connections[allindex]);
       LOGAR("after TCPConnect *con=static_cast<TCPConnect*>(connections[allindex]);");
        if(!con||current&wakeend) {
            if(con) {
#ifndef NOLOG
                int recsock= con->getReceiverSock(); 
#endif
                con->closeReceiverConnection();
                LOGGER("end activereceivethread %d close(%d)\n",h,recsock);
                }
            else
                LOGGER("end activereceivethread %d \n",h);
            LOGAR("before delete");
            delete active_receive[h];
            LOGAR("after delete");
            active_receive[h]=nullptr;
            LOGAR("return");
            return;
            }
        
        int &sock=con->getSenderSock();;
        int wassock=sock;
        sock=-1;
#ifdef WEAROS_MESSAGES
    if(!pass->wearos||!wearmessages[allindex])  //TODO use it?
#endif  
    {
        auto *con=static_cast<TCPConnect*>(connections[allindex]);
        if(!con) {
            return;
            }
        if(con->makeconnection(pass,ctxptr,sayactivereceive(pass))<0) {
            continue;
            }
//        status.hassocket=true;
     
        con->setReceiverSock(sock);;
        sock=wassock;
        void    receiversockopt(int sock) ;
        receiversockopt(sock) ;
        LOGAR("before activegetcommands");
        auto *con2=connections[allindex];
        if(!con2) {
            return;
            }
        con2->activegetcommands(pass,ctxptr);
        if(connections[allindex]) {
            LOGGER("after activegetcommands close(%d)\n",sock);
            sockclose(sock);
            }
         else {
            LOGAR("after activegetcommands");
            }
        }

        }
    }
bool hasnetwork() {
    return     backup&&backup->gethostnr()>0;
    }


void       makeWakeThread(int allindex,passhost_t *hostptr) {
    auto *con=connections[allindex];
    if(!con)  {
        LOGGER("makeWakeThread: connections[%d]==NULL\n",allindex);
        return;
    }
    if(con->senduprunning.test_and_set()) {
        LOGGER("makeWakeThread: sendup %d already running\n",allindex);
        return;
    }
    else {
        LOGGER("makeWakeThread: %d senduprunning set\n",allindex);
        }
    std::thread wake(sendup,hostptr);
    wake.detach();
}
void updatedata::wakesender(uintptr_t kind) {
    LOGAR("wakesender");
    for(int i=0;i<hostnr;i++) {
        passhost_t &host=allhosts[i];
        if(host.deactivated) {
            LOGGER("%d deactivated\n", i);
            }
        else {
        if(
    #ifdef WEAROS_MESSAGES
        !(wearmessages[i]&&host.wearos)&&
    #endif
        host.activereceive) {
            auto ind=host.activereceive-1;
            LOGGER("active %d\n",ind);
            if(active_receive[ind])  {
                active_receive[ind]->wakebackup(kind);
                }
            }
        else {
                if(host.receivefrom==3&&host.index<0) {
    #ifdef WEAROS_MESSAGES
                if(host.wearos&&wearmessages[i]) { //TODO
                LOGAR("wearos messages");
                }  else
    #endif
                {    
                    makeWakeThread(i,&host);
                    }
                    }
                else {
                    if(host.index>=0&&backup->con_vars[host.index])  {
                        LOGGER("con_vars[%d]->wakebackup\n",host.index);
                          backup->con_vars[host.index]->wakebackup(wakesend);
                          }
                          
                    }
            }
            }
       }
    }


extern void setDeactivated(int index,bool deactive) ;
void setDeactivated(int index,bool deactive) {
    auto &host=backup->getupdatedata()->allhosts[index];
    host.deactivated=deactive;
    if(!deactive)
        setConnectTime(index,0);
    }
void updatedata::wakestreamsender() {
    LOGAR("wakestreamsender");
    for(int i=0;i<hostnr;i++) {
        passhost_t &host=allhosts[i];
        if(host.deactivated) {
        LOGGER("deactivated %d\n",i);
        }
    else {
    if(
#ifdef WEAROS_MESSAGES
    !(wearmessages[i]&&host.wearos)&&
#endif
    host.activereceive) {
            auto ind=host.activereceive-1;
            LOGGER("active %d\n",ind);
            if(active_receive[ind])
                active_receive[ind]->wakebackup(wakestream);
            }
        else {
            if(host.receivefrom==3&&host.index<0) {
                makeWakeThread(i,&host);
                }
            else {
                if(host.index>=0&&backup->con_vars[host.index]) {
                    LOGGER("host.index=%d\n",host.index);
                      backup->con_vars[host.index]->wakebackup(wakestreamsend);
                      }
                      
                }
            }
        }
        }
    }


void TCPConnect::passivesender(passhost_t *pass,int &recsock,int oldrecsock)  {
    LOGGER("passivesender %d\n",getReceiverIdent());
     if(!networkpresent) {
        LOGGER("!networkpresent close and return sock=%d\n",getSenderIdent());
        closeSenderConnection();
        closeReceiverConnection();
        return;
        }
    int h=pass->index;
    updateone &host=backup->getupdatedata()->tosend[h];
    LOGAR("passivesender got host");
    if(h>=0&&backup->con_vars[h]) {
        const bool haspas= pass->haspass();
        if(haspas) {
            LOGAR("passivesender  haspas true");
            if(!receivepassinit(pass,host.getcrypt()))  {
                LOGGER("close(%d)\n",getReceiverIdent());
                closeReceiverConnection();
                closeSenderConnection();
                return ;
                }
            }
        else
            LOGAR("passivesender  haspas false");
         
        setSenderSock(recsock);
        recsock=oldrecsock;
        setSenderTimeouts();
//        host.setsock(sock); 
        LOGGER("wakebackup con_vars[%d]\n",h);
         backup->con_vars[h]->wakebackup(wakeall);
         }
     else {
         LOGGER("passivesender h>=0&&backup->con_vars[h] failed h=%d\n",h);
       }
    }

    


bool getpassive(int pos) {
    if(pos<backup->getupdatedata()->hostnr)  {
        const auto &host=backup->getupdatedata()->allhosts[pos];
        return host.getPassive();
        }
    return false;
    }
bool getactive(int pos) {
    if(pos<backup->getupdatedata()->hostnr)  {
        const auto &host=backup->getupdatedata()->allhosts[pos];
        LOGGER("receivefrom=%d sendpassive=%d activereceive=%d\n",host.receivefrom,host.sendpassive,host.activereceive);
        return host.getActive();
        }
    return false;
    }
    /*
updateone &getsendto(int index) {
         int tohost=backup->getupdatedata()->allhosts[index].index;
          return backup->getupdatedata()->tosend[tohost];
    }*/

updateone &getsendto(const passhost_t *host) {
        const int tohost=host->index;
          return backup->getupdatedata()->tosend[tohost];
    }
updateone &getsendto(int index) {
    return getsendto(backup->getupdatedata()->allhosts + index);
    }

bool sendall(const passhost_t *host) {
 const updateone &sender=getsendto(host);
  return (sender.sendnums&&sender.sendstream&&sender.sendscans);
  }

mirrorstatus_t mirrorstatus[maxallhosts];
extern void resethost(passhost_t &host) ;
void resethost(passhost_t &host) {
    backup->resethost(host);
    }

#include <mutex>
std::mutex change_host_mutex;
void resensordata(int sensorindex) {
    backup->resensordata(sensorindex);    
    }

int getgetsendnr() {
    if(backup)
       return backup->getupdatedata()->sendnr;
    return 0;
    }
void wakesender() {
     backup->getupdatedata()->wakesender();    
     }
#include "mirrorerror.h"
char mirrorerrors[maxallhosts][maxmirrortext];
int getindex(const  passhost_t *host) {
    return host-backup->getupdatedata()->allhosts;
    }
char *getmirrorerror(const passhost_t *pass) {
    int index=getindex(pass);
    return mirrorerrors[index];
    }
int savemessage(const passhost_t *pass,const char* fmt, ...){
    va_list args;
    va_start(args, fmt);
    char *buf=getmirrorerror(pass);
    int len=vsnprintf(buf,maxmirrortext, fmt, args);
    va_end(args);
    return len;
    }
    /*
void saveerror(const passhost_t *pass,const char* fmt, ...){
    int waser=errno;
        va_list args;
        va_start(args, fmt);
    char *buf=getmirrorerror(pass);
    int len=vsnprintf(buf,maxmirrortext, fmt, args);
    va_end(args);
    strcpy(buf+len,": ");
    len+=2;
    if(len<maxmirrortext)
        strerror_r(waser, buf+len, maxmirrortext-len);
    } */

void savebuferror(char *buf,int maxbuf,const char* fmt, ...){
    int waser=errno;
    va_list args;
    va_start(args, fmt);
    int len=vsnprintf(buf,maxbuf, fmt, args);
    va_end(args);
    strcpy(buf+len,": ");
    len+=2;
    if(len<maxbuf)
        strerror_r(waser, buf+len, maxbuf-len);
    }


void    sendstartsensors(int startpos) {
    LOGGER("sendstartsensors(%d)\n",startpos);
    const int maxint=backup->getupdatedata()->sendnr;
    for(int i=0;i<maxint;i++) {
        auto &host=backup->getupdatedata()->tosend[i];
        LOGGER("%i %d\n",i,host.startsensors);
        if(host.startsensors>startpos)
            host.startsensors=startpos;
        }
    }


extern void setCalibrates(uint16_t sensorindex) ;

void setCalibrates(uint16_t sensorindex) {
    LOGGER("setCalibrates(%hd)\n",sensorindex);
    const int maxint=getgetsendnr();
    for(int i=0;i<maxint;++i) {
        auto &host=backup->getupdatedata()->tosend[i];
        if(host.sendnums)
            host.setCalibrate(sensorindex);
        }
    }

Connect *connections[maxallhosts];
int hostsocks[maxallhosts]{-1,-1,-1,-1,-1,-1,-1,-1};
std::vector<int> sendsocks;


#include "net/ICE/ICEConnect.hpp"
Backup::Backup(std::string_view base): mapdata(base,backupdat,sizeof(struct updatedata)), con_vars((resetindices(),getupdatedata()->sendnr)) {
   const int len=getupdatedata()->hostnr;
   auto *allhosts=getupdatedata()->allhosts;
   for(int i=0;i<len;++i) {
     auto *host= allhosts+i;
      if(host->ICE)
          connections[i]=new ICEConnect(i,*host);
      else
          connections[i]=new TCPConnect(i);
      }
   sendsocks.resize(getupdatedata()->sendnr, -1);
   crypts.reserve(getupdatedata()->sendnr);
   for(int i=0;i<getupdatedata()->sendnr;i++) {
       auto &host=getupdatedata()->tosend[i];
       if(settings->data()->initVersion<31) { 
          LOGGER("%d set sendjugglucoid=false\n",i);
          host.sendjugglucoid=false;
#ifndef WEAROS
          if(getupdatedata()->allhosts[host.allindex].wearos) {
              if(settings->data()->sendnumbers)
                    host.sendLibre=true;
              if(settings->data()->saytreatments ||settings->data()->postTreatments)
                    host.sendNight=true;
            }
#endif
          }
       if(getupdatedata()->allhosts[host.allindex].haspass()) {
           auto cry=new crypt_t();
           LOGGER("crypts[%d]=%p=new crypt_t()\n",i,cry);
           crypts.push_back(cry);
           }
       else  {
           LOGGER("crypts[%d]=nullptr\n",i);
           crypts.push_back(nullptr);
           }
       }



   startactivereceivers();
   if(!getupdatedata()->port[0])
       strcpy(getupdatedata()->port,defaultport );
   if (getupdatedata()->NRturnserver > 1) {
      getupdatedata()->NRturnserver = 0;
      }
   if (getupdatedata()->NRturnserver) {
      auto &turn = getupdatedata()->turnserver[0];
      if (!turn.port) {
        turn.port = 3478;
        }
      if (!turn.hostname[0]) {
        turn.clear();
        getupdatedata()->NRturnserver = 0;
        }
      }

   void    backupbase(string_view basedir);
   backupbase(globalbasedir);
   for(int i=0;i<len;i++) {
       if(!getupdatedata()->allhosts[i].ICE&&getupdatedata()->allhosts[i].passive()) {
           startreceiver(false);
           break;
           }
       }


   shouldaskfordata=getshouldaskfordata();
   }

extern void startICEReceiver(passhost_t *host,ICEConnect *con);
int Backup::changeICEhost(const char *ICElabel,int index,const bool sendnums,const bool sendstream,const bool sendscans,const bool receive,string_view pass,uint32_t starttime,const char *label,bool side,bool startthreads) {
    const int hostnr=getupdatedata()->hostnr;
    LOGGER("hostnr=%d changeICEhost(%d,sendnums=%d,sendstream=%d,sendscans=%d,receive=%d,label=%s \n",hostnr,index,sendnums,sendstream,sendscans,receive,label);
    if(index<0) 
        index=hostnr;
    if(index>=maxallhosts)  {
        LOGAR("changeICEhost: index>=maxallhosts");
        return -3;
        }
    struct oldnet {
        bool wasnet=networkpresent;
        oldnet() {
            networkpresent=false;
            }
        ~oldnet() {
            if(!networkpresent)
                networkpresent=wasnet;
            };
        };
    struct oldnet desnet;
    const bool newhost=(index==hostnr);
    const bool sendto= sendnums|| sendstream|| sendscans;
    int tohost;
    bool newthread=false;
    auto &thehost=getupdatedata()->allhosts[index];
    LOGGER("changeICEhost newhost=%d thehost.index=%d\n",newhost,thehost.index);
    thehost.sendpassive=false;
    thehost.hostname=false;
    if(sendto) {
        if(newhost||thehost.index==-1) {  //Fout??
            tohost=getupdatedata()->sendnr;
            if(tohost>=maxsendtohost) {
                LOGGER("changeICEhost: tohost(%d)>=maxsendtohost(%d)\n",tohost,maxsendtohost);
                return -4;
                }
            thehost.index=tohost;
            newthread=true;
            }
        else  {
            tohost=thehost.index;
            }

        changereceiver(index,tohost,sendnums,sendstream,sendscans,false,pass.size(),starttime);
        }
    else {
        tohost=0;
        int sendindex=thehost.index;
        if(!newhost) {
            if(sendindex>=0) {
                deletestart(sendindex);
                thehost.index=-1;
                deleteend(sendindex); 
                setindices(index);
                }
            }
        thehost.index=-1;
        }
    thehost.side=side;
    thehost.setname(label);
    thehost.setICEname(ICElabel);
    thehost.noip=true;
    thehost.receivefrom=receive?3:1;
    thehost.deactivated=false;
    thehost.wearos=false;
    thehost.activereceive=0;

    setpass( thehost.pass,pass);

    LOGGER("changeICEhost receivefrom=%d\n", thehost.receivefrom);
    ICEConnect  *con;
    if(!newhost) {  
        if(thehost.ICE) {
            con=static_cast<ICEConnect*>(connections[index]);
            con->setindex(index);
            con->side=side;
            goto keepICE;
            }
        delete connections[index];
        }
    thehost.ICE=true;
    con= new ICEConnect(index,thehost);
    connections[index]=con;
    keepICE:
    lastuptodate[index]=0;
    setConnectTime(index,0);
    if(newhost)  {
        ++(getupdatedata()->hostnr);
        LOGGER("new host %s ++hostnr=%d\n",thehost.getnameif(),getupdatedata()->hostnr);
        thehost.newconnection=true;
        }
    deupdated();
    closesocksone(index, getupdatedata()->allhosts + index);
    if(startthreads) {
        if(newthread)
            startthread(index,tohost);
        }
    shouldaskfordata=getshouldaskfordata();
    #ifdef WEAROS_MESSAGES
    extern    void clearnetworkcache();
    clearnetworkcache();
    #endif
    /*
    if(!sendto) {
        startICEReceiver(&thehost,con);
        } */
    startReceiverThread(index);
    LOGGER("changeICEhost=%d\n",index);
    return index;
    }
