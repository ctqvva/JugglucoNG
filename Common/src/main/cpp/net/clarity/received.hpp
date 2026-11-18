// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <span>
namespace clarity {
// These are inclusive, absolute positions in the sensor's storage. Read the
// completion counter before taking the view: a concurrent arrival may extend
// storage, but must not extend this snapshot beyond the captured counter.
template <class Sensor> auto receivedStream(const Sensor &sensor) {
    // NG has no native AiDex X stream (its AiDex driver is managed Kotlin).
    const bool bounded = sensor.isLibre3() || sensor.isDexcom();
    const int end = bounded ? int(sensor.getinfo()->lastLifeCountReceived) + 1 : 0;
    const auto rows = sensor.getPolldata();
    if (!bounded || rows.empty())
        return rows;
    // getPolldata() already omits pollstart/warmupstartpos. The counter is
    // not a length relative to that span. Its position must be offset first.
    const auto count = end - (rows.data() - sensor.beginpolls());
    return rows.first(count <= 0 ? 0 : std::min(rows.size(), std::size_t(count)));
}
// Only the Libre 3 history path uses this counter; it is a history position,
// not the minute/life-count ID stored in the history record.
template <class Sensor> int receivedHistoryEnd(const Sensor &sensor) {
    const int end = int(sensor.getinfo()->lastHistoricLifeCountReceivedPos) + 1;
    return std::min(end, sensor.getScanendhistory());
}
} // namespace clarity
