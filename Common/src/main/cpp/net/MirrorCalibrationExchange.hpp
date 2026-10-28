#pragma once

#include <cstddef>
#include <cstdint>
#include <string_view>

// Uses the existing saskfile/dataonly envelope. A zero-length read probes
// the current profile's byte length; legacy peers answer zero (no return path).
inline constexpr uint32_t mirrorCalibrationMaximumBytes = 256U * 1024U;
inline constexpr std::string_view mirrorCalibrationPrefix = "mirror/calibration/";
inline constexpr std::string_view mirrorCalibrationSuffix = ".json";

inline bool isMirrorCalibrationProfilePath(std::string_view path) {
    if (!path.starts_with(mirrorCalibrationPrefix) ||
        !path.ends_with(mirrorCalibrationSuffix)) return false;
    const auto id = path.substr(mirrorCalibrationPrefix.size(),
        path.size() - mirrorCalibrationPrefix.size() - mirrorCalibrationSuffix.size());
    return !id.empty() && id != "." && id != ".." &&
        id.find_first_of("/\\") == std::string_view::npos;
}

inline std::string_view mirrorCalibrationSerial(std::string_view path) {
    if (!isMirrorCalibrationProfilePath(path)) return {};
    return path.substr(mirrorCalibrationPrefix.size(),
        path.size() - mirrorCalibrationPrefix.size() - mirrorCalibrationSuffix.size());
}

// Return -1 instead of a truncated profile if it grew after the size probe.
inline int32_t mirrorCalibrationResponseLength(size_t profileBytes,
                                               uint32_t offset, uint32_t requested) {
    if (offset != 0 || profileBytes == 0 || profileBytes > mirrorCalibrationMaximumBytes ||
        requested > mirrorCalibrationMaximumBytes) return -1;
    if (requested == 0 || profileBytes <= requested) return static_cast<int32_t>(profileBytes);
    return -1;
}
