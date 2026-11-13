#pragma once
/* How the Nightscout upload walks its history, apart from what it sends.

   The uploader thread used to run one pass over every sensor to the end before it looked at
   anything else. A reading that arrived meanwhile only set a bit, picked up once the pass was
   done: with a new server and 30 days of history, an hour in which Nightscout, and everyone
   following it, showed a stale value while the phone had a current one.

   A pass now gives way: after each request that went out it asks whether a live reading (or
   shutdown) is waiting, and if so ends as `interrupted`. The thread starts the next pass at
   once; that pass uploads the live reading first and resumes the history where the
   per-sensor cursor (`nightiter`) says it stopped.

   Kept free of the sensor store, the settings and the network so the rules can be run on a
   host (tools/test-nightscout-backlog.py). */
#include <algorithm>
#include <cstdint>

namespace nightscout_backlog {

/* How one pass ended. `interrupted` is not a failure: nothing was refused and nothing is
   backed off. The pass stopped so the live reading goes first, and runs again straight away. */
enum class Pass { done, failed, interrupted };

/* How one sensor's turn in a pass ended. `busy` is a sensor that had something to send or is
   still receiving: the pass must keep visiting it. */
enum class Turn { idle, busy, failed, interrupted };

struct Sweep {
    Pass pass;
    /* What to store as the lowest sensor the next pass has to visit. */
    int nightsensor;
};

/* Visit the sensors newest to oldest, from `last` down to `startsensor`.

   `nightsensor` says that every sensor below it has nothing left. A pass that ran to the end
   may raise it to the lowest sensor that still had work. A pass that stopped early may not:
   it has not seen the older sensors, so only `startsensor` is still known to be true. Raising
   it anyway, to the sensor it stopped on, left everything older never visited again until
   someone pressed Resend. */
template <class Visit>
Sweep sweep(const int last, const int startsensor, Visit &&visit) {
    int newstart = startsensor;
    for (int sensorid = last; sensorid >= startsensor; --sensorid) {
        switch (visit(sensorid)) {
        case Turn::failed:
            return {Pass::failed, startsensor};
        case Turn::interrupted:
            return {Pass::interrupted, startsensor};
        case Turn::busy:
            newstart = sensorid;
            break;
        case Turn::idle:
            break;
        }
    }
    return {Pass::done, newstart};
}

/* What one reading came to. `covered` was already sent ahead of the cursor. */
enum class Item { skip, covered, sent, refused };

/* Upload positions [pos,len) one by one, each its own request.

   `persisted()` is the stored cursor, which something else may have moved on. `settle(next)`
   stores that everything before `next` is done. After each request that went out `giveWay()`
   is asked, and a yes ends the turn there: the cursor is already stored, so the next pass
   starts at the following reading and nothing is sent twice. */
template <class Persisted, class OneItem, class Settle, class GiveWay>
Turn drainEach(int pos, const int len, Persisted &&persisted, OneItem &&item, Settle &&settle, GiveWay &&giveWay) {
    bool sent = false;
    for (; pos < len; pos++) {
        const int stored = persisted();
        if (stored > pos) {
            pos = stored - 1;
            continue;
        }
        switch (item(pos)) {
        case Item::skip:
            break;
        case Item::covered:
            settle(pos + 1);
            break;
        case Item::sent:
            settle(pos + 1);
            sent = true;
            if (giveWay())
                return Turn::interrupted;
            break;
        case Item::refused:
            return Turn::failed;
        }
    }
    return sent ? Turn::busy : Turn::idle;
}

/* What one chunk came to: nothing was left in it after skipping, it went out, or it was refused. */
enum class Chunk { nothing, sent, refused };

/* The same for the v1 array upload: `limit(pos)` says how many positions the next chunk
   takes, `send(pos,end)` uploads [pos,end). */
template <class Persisted, class Limit, class Send, class Settle, class GiveWay>
Turn drainChunks(int pos, const int len, Persisted &&persisted, Limit &&limit, Send &&send, Settle &&settle, GiveWay &&giveWay) {
    bool sent = false;
    while (pos < len) {
        pos = std::max(pos, static_cast<int>(persisted()));
        if (pos >= len)
            break;
        const int end = std::min(len, pos + limit(pos));
        switch (send(pos, end)) {
        case Chunk::refused:
            return Turn::failed;
        case Chunk::nothing:
            settle(end);
            pos = end;
            break;
        case Chunk::sent:
            settle(end);
            pos = end;
            sent = true;
            if (giveWay())
                return Turn::interrupted;
            break;
        }
    }
    return sent ? Turn::busy : Turn::idle;
}

/* What a pass leaves for the next one. Only the wakes that asked for glucose are carried: a
   pass that is done has nothing to hand on, while one that was refused or that gave way still
   owes the rest of its history and must be run again. */
inline std::uintptr_t carriedAfter(const Pass pass, const std::uintptr_t current, const std::uintptr_t glucosewakes) {
    return pass == Pass::done ? 0 : (current & glucosewakes);
}

/* The newest readings that were sent ahead of the cursor, per sensor.

   A live reading is sent first, out of order, while the history is still being walked from
   the oldest. The cursor must not send it again when it gets there, and must not skip one
   that was never sent: a range is only extended by a reading that directly follows its end,
   which is exact. A reading that follows after a gap starts a new range, so the ones in
   between are sent by the cursor in due course. */
template <int Sensors>
class RecentUploads {
    struct Range {
        uint32_t start{};
        uint32_t end{};
    };
    Range ranges[Sensors]{};

    Range *slot(const int sensorid, const bool ensure) {
        if (sensorid < 0 || sensorid >= Sensors)
            return nullptr;
        Range *range = &ranges[sensorid];
        if (!ensure && !range->start && !range->end)
            return nullptr;
        return range;
    }
    const Range *slot(const int sensorid) const {
        return const_cast<RecentUploads *>(this)->slot(sensorid, false);
    }

public:
    void clear() {
        for (auto &range : ranges)
            range = {};
    }
    /* `time` was sent. `before` is the time of the reading just before it on the same
       sensor, 0 when that is not known. */
    void remember(const int sensorid, const uint32_t time, const uint32_t before) {
        if (!time)
            return;
        if (Range *range = slot(sensorid, true)) {
            if (range->end && before && range->end == before && time >= range->end)
                range->end = time;
            else
                *range = {time, time};
        }
    }
    /* The cursor is at `time`: a range it has left behind is not needed any more. */
    void prune(const int sensorid, const uint32_t time) {
        Range *range = slot(sensorid, false);
        if (range && range->end && range->end + 600 < time)
            *range = {};
    }
    bool covered(const int sensorid, const uint32_t time) const {
        const Range *range = slot(sensorid);
        return range && range->start && time >= range->start && time <= range->end;
    }
};

} // namespace nightscout_backlog
