// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
namespace clarity {
// Return the full saved stream/history range and let the caller filter each
// slot with valid(). lastLifeCountReceived and lastHistoricLifeCountReceivedPos
// stop at the earliest missing backfill slot, so bounding by them would hold
// back newer live readings whenever a gap has not been backfilled yet.
template <class Sensor> auto receivedStream(const Sensor &sensor) {
    return sensor.getPolldata();
}
template <class Sensor> int receivedHistoryEnd(const Sensor &sensor) {
    return sensor.getScanendhistory();
}
} // namespace clarity
