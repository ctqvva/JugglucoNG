#pragma once
#include "SensorGlucoseData.hpp"
#include "sensoren.hpp"
extern Sensoren *sensors;

struct streamdata {
  int libreversion;
  int sensorindex;
  SensorGlucoseData *hist;
  streamdata(int libreversion, int sensorindex, SensorGlucoseData *sens)
      : libreversion(libreversion), sensorindex(sensorindex), hist(sens) {
    // Reference counting: prevents removeunused() from deleting sensors with
    // active streams
    if (hist)
      hist->incUsage();
  }
  streamdata(int libreversion, int sensorindex)
      : streamdata(libreversion, sensorindex,
                   sensors->getSensorData(sensorindex)) {}
  streamdata(int libreversion, const char *sensorname)
      : streamdata(libreversion, sensors->sensorindex(sensorname)) {}
  virtual bool good() const { return true; };
  virtual ~streamdata() {
    // Reference counting: decrements when stream is destroyed
    if (hist)
      hist->decUsage();
  };
};
struct libre3stream : streamdata {
  libre3stream(int sensindex, SensorGlucoseData *sens)
      : streamdata(3, sensindex, sens) {};
};
#ifdef DEXCOM
struct accustream : streamdata {
  accustream(int sensindex, SensorGlucoseData *sens)
      : streamdata(0x20, sensindex, sens) {};
};

#include "air/air.hpp"
#include <sys/stat.h>
// Maps one T from dir/name. A missing or short file is a sensor seen for the
// first time; it starts from T's member defaults rather than zeros.
template <typename T>
inline Mmap<T> mmapWithDefaults(std::string_view dir, std::string_view name) {
  const pathconcat path(dir, name);
  struct stat st;
  const bool fresh = stat(path, &st) != 0 || st.st_size < (off_t)sizeof(T);
  Mmap<T> map(path, 1);
  if (fresh && map.data())
    *map.data() = T{};
  return map;
}
struct airstream : streamdata {
  Mmap<DeviceInfo3Obj> sensorInfo;
  Mmap<air1_opcal4_arguments_t> generated;
  int tmpiter = 0;
  int tmptot = 0;
  int ininfo = 0;
  int errors = 0;
  // Too large for the JNI thread's stack.
  air_input input;
  air1_opcal4_output_t output;
  air1_opcal4_debug_t debug;
  airstream(int sensindex, SensorGlucoseData *sens)
      : streamdata(careSensAirKind, sensindex, sens),
        sensorInfo(mmapWithDefaults<DeviceInfo3Obj>(hist->getsensordir(),
                                                    sensorInfoStr)),
        generated(hist->getsensordir(), generatedStr, 1) {}
  bool setNumberNew(int nr);
};
#endif
struct aidexstream : streamdata {
  aidexstream(int sensindex, SensorGlucoseData *sens)
      : streamdata(0x100, sensindex, sens) {};
};
#ifdef SIBIONICS
#include "sibionics/SiContext.hpp"
struct sistream : streamdata {
  SiContext sicontext;
  sistream(int sensindex, SensorGlucoseData *sens)
      : streamdata(0x10, sensindex, sens), sicontext(sens) {};
};
#endif

#ifdef DEXCOM
#include "dexcom/DexContext.hpp"
struct dexcomstream : streamdata {

  DexContext dexcontext;
  dexcomstream(int sensindex, SensorGlucoseData *sens)
      : streamdata(0x40, sensindex, sens), dexcontext(sens) {};
};
#endif
