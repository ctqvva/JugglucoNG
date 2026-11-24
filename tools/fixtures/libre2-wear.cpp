// Built by tools/test-libre2-processing.py: the production backend around the end of a sensor's wear.
#include <algorithm>
#include <cstdio>
#include <memory>
#include <vector>
#include "libre2_imp/backend.cpp"

namespace ar = libre2_processing;
#define CHECK(c, ...) do { if(!(c)) { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); return 1; } } while(0)

struct Bytes {
    std::vector<unsigned char> memory;
    Bytes(const void *from, int len) : memory(len + 16) { get()->len = len; std::memcpy(get()->data(), from, len); }
    data_t *get() { return reinterpret_cast<data_t *>(memory.data()); }
};
static void crc(unsigned char *at, const unsigned char *from, unsigned len) {
    ar::write_state_u16(at, ar::state_crc(std::span<const std::uint8_t>(from, len)));
}
constexpr unsigned start = 500000000;
constexpr unsigned adc = 600, thermistor = 1500;  // about 100 mg/dL at 32 degrees
static unsigned char uid[8]{1, 2, 3, 4, 5, 6, 7, 0xe0};

// A made-up sensor of the second generation, whose frames reach the backend decrypted.
struct Sensor {
    unsigned char patch[6]{0x76, 0, 0, 0, 0, 0};
    unsigned wear;
    explicit Sensor(unsigned char type) { patch[2] = type; wear = ar::nominal_wear_minutes(type); }
    std::array<unsigned char, 344> fram(unsigned age, unsigned footer) const {
        std::array<unsigned char, 344> f{};
        ar::write_state_u16(f.data() + 2, 1 | 100 << 3);
        f[4] = 3;
        f[26] = age % 16; f[27] = ((age - 2) / 15) % 32;
        for(unsigned i = 0; i < 48; ++i) {
            const std::uint64_t record = adc | std::uint64_t(thermistor) << 26;
            for(unsigned j = 0; j < 6; ++j) f[28 + 6 * i + j] = (record >> (8 * j)) & 0xff;
        }
        ar::write_state_u16(f.data() + 316, age);
        f[0x142] = patch[2];
        ar::write_state_u16(f.data() + 0x146, footer);
        ar::write_state_u16(f.data() + 0x144, 200);
        // Factory footer: no baseline, a full scale of 10000 and the thermistor reference.
        ar::write_state_u16(f.data() + 0x151, 10000);
        f[0x156] = (thermistor & 15) << 4; f[0x157] = thermistor >> 4;
        crc(f.data(), f.data() + 2, 22);
        crc(f.data() + 24, f.data() + 26, 294);
        crc(f.data() + 320, f.data() + 322, 22);
        return f;
    }
    std::array<unsigned char, 46> frame(unsigned age) const {
        std::array<unsigned char, 46> p{};
        for(unsigned i = 0; i < 10; ++i) ar::write_processing_u32(p.data() + 2 + 4 * i, adc | thermistor << 14);
        ar::write_state_u16(p.data() + 42, age);
        crc(p.data() + 44, p.data() + 2, 42);
        return p;
    }
    // The state of a running sensor, as activation left it.
    std::unique_ptr<scanstate> state(unsigned stored_start = start, int owner = libre2_imp::open_backend_state) const {
        ar::ActivationOptions options{};
        options.wear_minutes = wear;
        const auto initial = ar::make_initial_state_eu(fram(100, 0), options);
        const auto active = ar::activation_complete_eu(initial.state, ar::activation_day_from_start(stored_start), stored_start);
        auto s = std::make_unique<scanstate>();
        auto *d = s->alloc(active.state.size());
        std::memcpy(d->data(), active.state.data(), active.state.size());
        s->setpos(MESS, d);
        s->datpos(FAKE) = owner;
        return initial.accepted && active.accepted ? std::move(s) : nullptr;
    }
};
static const unsigned char *config(scanstate &s) {
    return reinterpret_cast<const unsigned char *>(s.get(MESS)->data()) + ar::state_header_size;
}
static bool glucose(const AlgorithmResults &r) {
    return !r.glu->getQuality() && r.glu->getValue() > 90 && r.glu->getValue() < 110;
}
struct Outcome { bool read, past_wear; };
// One frame at a phone clock and sensor age; an accepted one leaves its state in s.
static Outcome stream(const Sensor &sensor, std::unique_ptr<scanstate> &s, unsigned elapsed_seconds, unsigned age) {
    Bytes u(uid, 8), p(sensor.patch, 6), f(sensor.frame(age).data(), 46);
    auto next = std::make_unique<scanstate>();
    bool past = false;
    std::unique_ptr<AlgorithmResults> r(libre2_imp::stream(u.get(), p.get(), f.get(), s.get(), next.get(),
        start + elapsed_seconds, start, true, nullptr, &past));
    if(r) s = std::move(next);
    return {r && glucose(*r), past};
}
static bool kept(scanstate &s, const Sensor &sensor, unsigned seen) {
    Bytes p(sensor.patch, 6);
    return ar::read_state_u32(config(s)) == seen && ar::read_state_u16(config(s) + 0x14) == sensor.wear &&
        libre2_imp::usable_state(&s, p.get());
}
static int bluetooth() {
    constexpr unsigned day = 1440;
    for(unsigned char type : {0x39, 0x3a, 0x3b, 0x3c, 0x79}) {
        const Sensor sensor(type);
        auto s = sensor.state();
        CHECK(s && kept(*s, sensor, start), "%02x: no state to start from", type);
        const unsigned w = sensor.wear;
        // Every boundary in one run, so a state saved past the nominal wear is read again.
        for(unsigned m : {w - 61, w - 60, w - 1, w, w + 1, w + day / 2, w + day / 2 + 1, w + day - 1, w + day}) {
            const auto r = stream(sensor, s, m * 60, m);
            CHECK(r.read && !r.past_wear, "%02x: frame at nominal %+d min refused", type, int(m - w));
            CHECK(kept(*s, sensor, start), "%02x: start or nominal wear changed at %+d min", type, int(m - w));
        }
        // The last minute counts to its end; the next one is past the wear.
        auto last = stream(sensor, s, (w + day) * 60 + 59, w + day);
        CHECK(last.read, "%02x: the last minute is cut short", type);
        for(unsigned m : {w + day + 1, w + day + 2, w + 2 * day}) {
            const auto r = stream(sensor, s, m * 60, m);
            CHECK(!r.read && r.past_wear, "%02x: frame at nominal %+d min not past the wear", type, int(m - w));
        }
        // A wrong phone clock is not the end of the sensor.
        for(unsigned seconds : {(w + 30 * day) * 60, 0u - 3600u}) {
            auto young = sensor.state();
            const auto r = stream(sensor, young, seconds, w / 2);
            CHECK(!r.read && !r.past_wear, "%02x: a wrong clock ended the sensor", type);
        }
    }
    return 0;
}
static int moved_start() {
    // The vendor path's last-day state has its start a day late; the saved start comes back.
    const Sensor sensor(0x39);
    for(unsigned m : {sensor.wear - 30, sensor.wear + 1440}) {
        auto s = sensor.state(start + 86400, 1);
        const auto r = stream(sensor, s, m * 60, m);
        CHECK(r.read && kept(*s, sensor, start), "a moved start was kept at minute %u", m);
    }
    auto s = sensor.state(start + 86400, 1);
    const unsigned after = sensor.wear + 1441;
    const auto r = stream(sensor, s, after * 60, after);
    CHECK(!r.read && r.past_wear, "a moved start gave more than a day");
    return 0;
}
static int scans() {
    for(unsigned char type : {0x39, 0x3a}) {
        const Sensor sensor(type);
        const unsigned w = sensor.wear;
        // With the sensor still recording, and with its records capped at the end it stores.
        for(unsigned footer : {0u, w + 720})
            for(unsigned m : {w - 60, w, w + 1, w + 720, w + 721, w + 1440, w + 1441}) {
                auto s = sensor.state();
                const auto f = sensor.fram(m, footer);
                const unsigned age = footer && m >= footer ? footer : m;
                auto image = f;
                image[26] = age % 16;
                crc(image.data() + 24, image.data() + 26, 294);
                Bytes u(uid, 8), p(sensor.patch, 6), data(image.data(), 344);
                auto next = std::make_unique<scanstate>();
                outobj from, to;
                std::unique_ptr<AlgorithmResults> r(libre2_imp::scan(u.get(), p.get(), data.get(), s.get(), next.get(),
                    start + m * 60, &from, &to, 60, start));
                CHECK(bool(r) == (m <= w + 1440), "%02x: scan at nominal %+d min (footer %u): %s", type, int(m - w), footer,
                    r ? "read" : "refused");
                CHECK(!r || footer || glucose(*r), "%02x: scan at nominal %+d min has no glucose", type, int(m - w));
                CHECK(!r || kept(*next, sensor, start), "%02x: a scan changed the start or nominal wear", type);
            }
    }
    return 0;
}
int main() {
    if(bluetooth() || moved_start() || scans()) return 1;
    std::puts("PASS: Libre 2 frames and scans at the nominal and extended wear");
}
