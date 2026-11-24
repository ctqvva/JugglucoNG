// Built by tools/test-libre2-processing.py against the production libre2_imp headers.
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <span>
#include "libre2_imp/processing/temporal_history.hpp"
#include "libre2_imp/processing/input_window.hpp"

using namespace libre2_processing;

#define CHECK(c, ...) do { if(!(c)) { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); return 1; } } while(0)

// One made-up glucose value per absolute 15-minute history index.
static float truth(unsigned index) { return 100.f + float(index); }

struct Window { unsigned raw_first, last, output_first, count; };
// The same index arithmetic as prepare_decoded_inputs and evaluate_numeric (delayed history).
static Window window(unsigned age) {
    const unsigned last = completed_history_index(std::uint16_t(age));
    return {unsigned(std::max(1, int(age/15)-31)), last, last>31 ? last-31 : 1, std::min(31u, last>1 ? last-1 : 0u)};
}
static std::array<PersistedHistory,31> report(unsigned age, bool scan, std::array<PersistedHistory,31> previous) {
    const auto w = window(age);
    std::array<TemporalSample,32> history{};
    std::array<float,32> correction{};
    for(unsigned i=0; i<32; ++i) {
        const unsigned index = w.raw_first+i;
        if(index<=w.last) history[i] = {truth(index), 0, 0};
        else history[i] = {0.f, 0x8000, 0};
    }
    return merge_reported_history(previous, history.data(), correction.data(), w.raw_first, w.output_first,
        500, 40, scan, w.count, {});
}
static std::array<PersistedHistory,31> none() {
    std::array<PersistedHistory,31> h{};
    for(auto &p: h) p = {0, 0xffff, 0, 0.f};
    return h;
}
static int history() {
    // A minute's value must not depend on which side of the quarter hour the scan falls.
    for(unsigned age=60; age<21600; ++age) {
        const auto w = window(age);
        const auto out = report(age, true, none());
        unsigned filled = 0;
        for(unsigned i=0; i<31; ++i) {
            if(out[i].quality) continue;
            ++filled;
            const unsigned index = out[i].minute/15u;
            CHECK(index<=w.last, "age %u reports minute %u before it is complete", age, out[i].minute);
            // Flat slope: every history projection stays within half a step of the true sample.
            const float want = scan_curve(truth(index));
            const float got = out[i].filtered_glucose;
            CHECK(got>want-.75f && got<want+.75f, "age %u: minute %u holds %.2f, its own sample gives %.2f",
                age, out[i].minute, got, want);
        }
        CHECK(filled+1>=w.count, "age %u: only %u of %u history points", age, filled, w.count);
        // A stream frame adds only the newest point, with that minute's sample.
        const auto live = report(age, false, none());
        if(w.count) {
            const auto &p = live[w.count-1];
            CHECK(!p.quality, "age %u: stream lost its newest point", age);
            const float want = scan_curve(truth(p.minute/15u));
            CHECK(p.filtered_glucose>want-.75f && p.filtered_glucose<want+.75f,
                "age %u: stream minute %u holds %.2f, its own sample gives %.2f", age, p.minute, p.filtered_glucose, want);
        }
    }
    return 0;
}

struct Sensor {
    bool isDexcom() const { return false; }
/* SENSOR_METHODS */
};
static int sensor_error() {
    Sensor s;
    // Three frames in a row, whatever the gaps between them.
    s.lastPacketTime = 1000; s.setSensorError(1000);
    CHECK(!s.hasSensorError(1000), "error shown after one frame");
    s.lastPacketTime = 1120; s.setSensorError(1120);
    CHECK(!s.hasSensorError(1120), "a missed packet made two frames count as three");
    s.lastPacketTime = 1180; s.setSensorError(1180);
    CHECK(s.hasSensorError(1180), "error not shown after three frames");
    s.clearSensorError();
    CHECK(!s.hasSensorError(1200), "error shown after a valid reading");
    for(unsigned t: {1240u, 1300u}) { s.lastPacketTime = t; s.setSensorError(t); }
    CHECK(!s.hasSensorError(1300), "frames from before the valid reading were counted");
    // Frames that come a little early still count.
    s.clearSensorError();
    for(unsigned t: {2000u, 2045u, 2090u}) { s.lastPacketTime = t; s.setSensorError(t); }
    CHECK(s.hasSensorError(2090), "three quick frames not counted");
    // Ten silent minutes start over; ten failing minutes claim the sensor.
    s.lastPacketTime = 2090+600; s.setSensorError(2090+600);
    CHECK(!s.hasSensorError(2690), "frames from before a link loss were counted");
    for(unsigned t=2750; t<=2690+600; t+=60) { s.lastPacketTime = t; s.setSensorError(t); }
    CHECK(s.hasSensorError(3290) && s.replacesensor, "no replace hint after ten minutes");
    return 0;
}
int main() {
    if(history() || sensor_error()) return 1;
    std::puts("PASS: Libre 2 history minutes, three-frame sensor error");
}
