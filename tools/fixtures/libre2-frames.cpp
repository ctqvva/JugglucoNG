// Built by tools/test-libre2-processing.py: the production frame path from the JNI call down to
// the backend's verdict, with a scripted backend.
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <utility>
#include <vector>
#include <unistd.h>

#define HASP1 1
#define CHECK(c, ...) do { if(!(c)) { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); return 1; } } while(0)
#define JNIEXPORT
#define JNICALL
#define fromjava(x) x
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
#define setthreadname(x) ((void)0)
#define NORAWSTREAM
using jlong = int64_t; using jbyte = int8_t; using JNIEnv = int; using jclass = void *; using jbyteArray = void *;
constexpr float convfactordL = 18.f;

static time_t clock_now;
#define time(x) clock_now

struct data_t { static void deleteex(data_t *d) { delete d; } };
static data_t *fromjbyteArray(JNIEnv *, jbyteArray) { return new data_t; }
template <class F> struct destruct { F f; ~destruct() { f(); } };
template <class F> destruct(F) -> destruct<F>;

struct jniHistory {};
struct GlucoseNow {
    int value, quality;
    int getValue() const { return value; }
    int getQuality() const { return quality; }
    int getId() const { return 1; }
    int trend() const { return 0; }
    float rate() const { return 0.f; }
};
struct AlgorithmResults {
    GlucoseNow now;
    jniHistory past;
    const GlucoseNow &currentglucose() const { return now; }
    const jniHistory &history() const { return past; }
};
static const AlgorithmResults *ALGDUP_VALUE = reinterpret_cast<const AlgorithmResults *>(1ul);
static const AlgorithmResults *ALGNO_VALUE = reinterpret_cast<const AlgorithmResults *>(2ul);

// What a saved state is worth, and which one the sensor directory holds now.
enum Saved { Missing, Incompatible, Usable };
static Saved disk = Usable;
static int loads;
constexpr int defaultscanstate = 4;
struct Directory {};
struct scanstate {
    Saved kind;
    explicit scanstate(int) : kind(Usable) {}
    explicit scanstate(Directory) : kind(disk) { ++loads; }
    bool good() const { return kind != Missing; }
};
namespace libre2_imp {
static bool usable_state(scanstate *state, const data_t *) { return state && state->kind == Usable; }
}
template <class B, class M, class... Ts> B *locknew(M &mu, Ts &&...args) {
    std::lock_guard<M> lock(mu);
    return new B(std::forward<Ts>(args)...);
}

struct Info { int warmup = 60, wearduration = 14 * 24 * 60; };
struct SensorGlucoseData {
    std::mutex mutex;
    Info info;
    int polls = 0;
    bool waiting = false;
    Info *getinfo() { return &info; }
    Directory getsensordir() const { return {}; }
    uint32_t getstarttime() const { return 100000; }
    int getWarmupSEC() const { return 60 * 60; }
    bool isDexcom() const { return false; }
    bool savepoll(uint32_t, int, int, int, float) { ++polls; return true; }
/* SENSOR_METHODS */
};
static std::pair<int, int> patchtimevalues(const data_t *) { return {60, 14 * 24 * 60}; }
static bool streamHistory() { return true; }
static int histories;
static void addStreamHistory(const jniHistory &, time_t, int, SensorGlucoseData &) { ++histories; }

struct libre2stream {
    SensorGlucoseData *hist;
    scanstate *state;
    int sensorindex = 0, startsincebase = 0;
    jbyte person = 0;
    libre2stream(SensorGlucoseData *h) : hist(h), state(new scanstate(Directory{})) {}
    ~libre2stream() { delete state; }
    const data_t *getident() const { return nullptr; }
    const data_t *getinfo() const { return nullptr; }
    void setstate(scanstate *next) { delete state; state = next; }
    const AlgorithmResults *processTooth(data_t *, scanstate *, uint32_t);
};
enum Verdict { Refused, PastWear, NoValue, Reading };
static Verdict verdict;
static int backend_calls;
static AlgorithmResults *processBlue(data_t *, jbyte, const data_t *, const data_t *, scanstate *, scanstate *,
        uint32_t, uint32_t, int, int, bool &pastWear) {
    ++backend_calls;
    pastWear = verdict == PastWear;
    if(verdict == Refused || pastWear) return nullptr;
    return new AlgorithmResults{verdict == Reading ? GlucoseNow{100, 0} : GlucoseNow{0, 0x8000}, {}};
}

struct sensor { bool finished = false; bool reactivateFromData() { return true; } };
struct Sensors { sensor one; sensor *getsensor(int) { return &one; } } sensors_, *sensors = &sensors_;
struct Backup { enum { wakestream }; void wakebackup(int) {} void resensordata(int) {} } backup_, *backup = &backup_;
static void savestate(libre2stream *) {}
static void wakewithcurrent() {}
static jlong glucoseback(uint32_t, uint32_t value, float, SensorGlucoseData *) { return jlong(value) * 10; }

/* FRAME_METHODS */

static jlong frame(libre2stream &s, Verdict v) {
    clock_now += 60;
    verdict = v;
    return processTooth(nullptr, nullptr, reinterpret_cast<jlong>(&s), nullptr);
}
static int refused_frames() {
    // A frame the backend refuses says nothing about the sensor, in the warm-up or after it.
    for(time_t start: {100000 + 10 * 60, 100000 + 24 * 3600}) {
        clock_now = start;
        disk = Usable;
        SensorGlucoseData h;
        libre2stream s(&h);
        for(int i = 0; i < 30; ++i) {
            CHECK(!frame(s, Refused), "a refused frame gave a reading");
            CHECK(!h.hasSensorError(clock_now) && !h.replacesensor, "refused frame %d counted as a sensor error", i + 1);
        }
    }
    // It does not end an error the sensor reported either, and a reading does.
    clock_now = 100000 + 24 * 3600;
    SensorGlucoseData h;
    libre2stream s(&h);
    for(int i = 0; i < 2; ++i) {
        const scanstate *before = s.state;
        frame(s, NoValue);
        CHECK(s.state != before, "a frame without a value lost the state it advanced");
    }
    CHECK(!h.hasSensorError(clock_now), "error shown after two frames without a value");
    const scanstate *kept = s.state;
    frame(s, Refused);
    CHECK(s.state == kept, "a refused frame replaced the state");
    CHECK(!h.hasSensorError(clock_now), "a refused frame counted as the third");
    frame(s, NoValue);
    CHECK(h.hasSensorError(clock_now), "error not shown after three frames without a value");
    frame(s, Refused);
    CHECK(h.hasSensorError(clock_now), "a refused frame cleared the error");
    CHECK(frame(s, Reading) == 1000 && !h.hasSensorError(clock_now), "a reading did not clear the error");
    // Frames without a value in the warm-up count toward nothing but keep their history.
    clock_now = 100000 + 10 * 60;
    SensorGlucoseData young;
    libre2stream w(&young);
    histories = 0;
    for(int i = 0; i < 20; ++i) frame(w, NoValue);
    CHECK(!young.hasSensorError(clock_now) && histories == 20, "warm-up gaps counted, or lost their history");
    return 0;
}
static int unusable_state() {
    // The frames wait for a scan without a sensor error. The first one after the scan reads from
    // the state it saved: the running stream needs no restart.
    for(Saved bad: {Missing, Incompatible}) {
        clock_now = 100000 + 24 * 3600;
        disk = bad;
        SensorGlucoseData h;
        libre2stream s(&h);
        backend_calls = 0;
        for(int i = 0; i < 15; ++i) {
            CHECK(!frame(s, Reading), "a frame was read without a usable state");
            CHECK(!h.hasSensorError(clock_now) && !h.replacesensor, "a state we cannot use counted as a sensor error");
            CHECK(h.isScanNeeded(clock_now), "no scan asked for");
        }
        // Frames that stopped arriving are a lost link, which a scan does not bring back.
        CHECK(!h.isScanNeeded(clock_now + 600), "a scan asked for without frames");
        CHECK(!backend_calls, "an unusable state reached the backend");
        disk = Usable;
        CHECK(frame(s, Reading) == 1000 && h.polls == 1, "the frame after the scan gave no reading");
        CHECK(!h.isScanNeeded(clock_now), "a scan still asked for after a reading");
        loads = 0;
        CHECK(frame(s, Reading) == 1000 && !loads, "a usable state was loaded again");
    }
    return 0;
}
static int past_wear() {
    clock_now = 100000 + 15 * 24 * 3600;
    disk = Usable;
    SensorGlucoseData h;
    libre2stream s(&h);
    for(int i = 0; i < 3; ++i) {
        CHECK(!h.hasSensorError(clock_now), "replace hint after %d frames", i);
        CHECK(!frame(s, PastWear), "a frame past the wear gave a reading");
    }
    CHECK(h.hasSensorError(clock_now) && h.replacesensor, "no replace hint for a sensor past its wear");
    CHECK(!h.isScanNeeded(clock_now), "a scan asked for a sensor past its wear");
    return 0;
}
int main() {
    if(refused_frames() || unusable_state() || past_wear()) return 1;
    std::puts("PASS: Libre 2 refused frames, state rebuilt under a running stream, frames past the wear");
}
