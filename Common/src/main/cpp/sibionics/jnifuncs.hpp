
algtype(getAlgorithmContextFromNative) vers(getAlgorithmContextFromNative);
algtype(initAlgorithmContext) vers(initAlgorithmContext);
algtype(processAlgorithmContext) vers(processAlgorithmContext);
algtype(getAlgorithmVersion) vers(getAlgorithmVersion);
algtype(releaseAlgorithmContext) vers(releaseAlgorithmContext);

algtype(getBinaryStructAlgorithmContext)
    vers(getBinaryStructAlgorithmContext) = nullptr;
algtype(setBinaryStructAlgorithmContext)
    vers(setBinaryStructAlgorithmContext) = nullptr;

static bool vers(getJNIfunctions)() {
  static std::string_view alglib = jniAlglib;
  void *handle = openlib(alglib);
  if (!handle) {
    LOGGER("dlopen %s failed: %s\n", alglib.data(), dlerror());
    return false;
  }
  {
    constexpr const char str[] = algjavastr(getAlgorithmContextFromNative);
    vers(getAlgorithmContextFromNative) =
        (algtype(getAlgorithmContextFromNative))dlsym(handle, str);
    if (!vers(getAlgorithmContextFromNative)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = algjavastr(getAlgorithmVersion);
    vers(getAlgorithmVersion) =
        (algtype(getAlgorithmVersion))dlsym(handle, str);
    if (!vers(getAlgorithmVersion)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = algjavastr(initAlgorithmContext);
    vers(initAlgorithmContext) =
        (algtype(initAlgorithmContext))dlsym(handle, str);
    if (!vers(initAlgorithmContext)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = algjavastr(processAlgorithmContext);
    vers(processAlgorithmContext) =
        (algtype(processAlgorithmContext))dlsym(handle, str);
    if (!vers(processAlgorithmContext)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = algjavastr(releaseAlgorithmContext);
    vers(releaseAlgorithmContext) =
        (algtype(releaseAlgorithmContext))dlsym(handle, str);
    if (!vers(releaseAlgorithmContext)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      return false;
    }
  }
  {
    constexpr const char str[] = algjavastr(getBinaryStructAlgorithmContext);
    vers(getBinaryStructAlgorithmContext) =
        (algtype(getBinaryStructAlgorithmContext))dlsym(handle, str);
    if (!vers(getBinaryStructAlgorithmContext)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      // return false;
    }
  }
  {
    constexpr const char str[] = algjavastr(setBinaryStructAlgorithmContext);
    vers(setBinaryStructAlgorithmContext) =
        (algtype(setBinaryStructAlgorithmContext))dlsym(handle, str);
    if (!vers(setBinaryStructAlgorithmContext)) {
      LOGGER("dlsym %s failed: %s\n", str, dlerror());
      // return false;
    }
  }

  typedef jint (*OnLoadtype)(JavaVM *vm, void *reserved);
  constexpr const char onloadname[] = "JNI_OnLoad";
  OnLoadtype OnLoad = (OnLoadtype)dlsym(handle, onloadname);
  if (!OnLoad) {
    LOGGER("dlsym %s failed\n", onloadname);
    return false;
  }
  LOGSTRING("found OnLoad\n");

  OnLoad(getnewvm(), nullptr);
  LOGAR("after OnLoad");
  return true;
}
extern struct JNINativeInterface envbuf;
double SiContext::vers(process)(int index, double value, double temp) {
  auto context = reinterpret_cast<jobject>(algcontext);
  const auto res = vers(processAlgorithmContext)(
      subenv, nullptr, context, index, value, temp, 0.0, targetlow, targethigh);
  LOGGER("processAlgorithmContext%d(%p,%d,%f,%f,%f,%f,%f)=%f\n", vers(0),
         algcontext, index, value, temp, 0.0, targetlow, targethigh, res);
  if (vers(getBinaryStructAlgorithmContext)) {
    binState.reset();
    jnidata_t hierjnidata = {&envbuf, &binState};
    JNIEnv *hiersubenv = (JNIEnv *)&hierjnidata;
    jbyteArray bar =
        vers(getBinaryStructAlgorithmContext)(hiersubenv, nullptr, context);
    //        LOGAR("getBinaryStructAlgorithmContext");
    data_t *data = (data_t *)bar;
    binState.setpos(0, data);
  } else {
    LOGAR("getBinaryStructAlgorithmContext==null");
  }
  return res;
};
/*
getjson_t vers(getjson);
setjson_t vers(setjson); */
#ifdef TEST
AlgorithmContext *vers(initAlgorithm)(const char *shortname) {
  char *version = (char *)vers(getAlgorithmVersion)(subenv, nullptr);
  LOGGER("getAlgorithmVersion()=%s\n", version);
  jobject jalg = vers(getAlgorithmContextFromNative)(subenv, nullptr);
  version = (char *)vers(getAlgorithmVersion)(subenv, nullptr);
  LOGGER("getAlgorithmVersion()=%s  algcontext=%p\n", version, jalg);
  int res = vers(initAlgorithmContext)(
      subenv, nullptr, jalg, 0, reinterpret_cast<jstring>((char *)shortname));
  LOGGER("initAlgorithmContext%d(...%s)=%d\n", vers(0), shortname, res);
  if (res != 1) {
    LOGGER("initAlgorithmContext(algcontext,0,%s)==%d\n", shortname, res);
    return nullptr;
  }
  auto algcontext = reinterpret_cast<AlgorithmContext *>(jalg);
  return algcontext;
}
#else
AlgorithmContext *vers(initAlgorithm)(SensorGlucoseData *sens,
                                      multimmap &binState) {
  jobject jalg = vers(getAlgorithmContextFromNative)(subenv, nullptr);
  char *shortname = sens->getinfo()->siBlueToothNum;
  int res = vers(initAlgorithmContext)(subenv, nullptr, jalg, 0,
                                       reinterpret_cast<jstring>(shortname));
  LOGGER("initAlgorithmContext%d(...%s)=%d\n", vers(0), shortname, res);
  if (res != 1) {
    const char *shortname;
    if (sens->notchinese()) {
      const int sub = sens->siSubtype();
      if (sub == 1) {
        shortname = "YMWD016F";
      } else {
        if (sub == 3) {
          shortname = "0316015A";
        } else {
          shortname = "YEZ1450H";
        }
      }
    } else {
      shortname = "GEPD802J";
    }
    res = vers(initAlgorithmContext)(
        subenv, nullptr, jalg, 0,
        reinterpret_cast<jstring>(const_cast<char *>(shortname)));
    LOGGER("initAlgorithmContext%d(...%s)=%d\n", vers(0), shortname, res);
    if (res != 1) {
      LOGGER("initAlgorithmContext(algcontext,0,%s)==%d\n", shortname, res);
      return nullptr;
    }
  }
  LOGGER("binState.map.data=%p\n", binState.map.data());
  // Don't check for state if in reset mode - prevents infinite loops
  if (sens->getSiIndex() > 1 && !sens->isInResetMode()) {
    data_t *saved = binState.get(0);
    if (saved) {
      LOGGER("setBinaryStructAlgorithmContex %p#%d\n", saved, saved->size());
      vers(setBinaryStructAlgorithmContext)(subenv, nullptr, jalg,
                                            (jbyteArray)saved);
    } else {
      // binState is empty. This happens legitimately after:
      // 1. SiContext::reset() zeroes binState + deletes the file
      // 2. Replay completes → exitResetMode()
      // 3. sistream destroyed (pause/disconnect) → binState mmap lost
      // 4. NEW sistream created (reconnect) → opens fresh empty binState file
      //
      // In this case, lastCalResetTime will be recent (set by SiContext::reset
      // or resetSiIndex). Calling resetSiIndex() here would re-enter reset
      // mode, trigger another full replay, exit reset mode, and then the NEXT
      // getdataptr() call repeats the cycle — an infinite loop.
      //
      // Instead: check if a reset was performed recently. If so, skip the
      // resetSiIndex — the algorithm will build state from incoming data.
      uint32_t lastReset = sens->getinfo()->lastCalResetTime;
      time_t now = time(nullptr);
      bool recentReset = (lastReset > 0 && (now - lastReset) < 30 * 60);
      if (recentReset) {
        LOGGER("initAlgorithm: binState empty but recent reset (%ld sec ago), "
               "skipping resetSiIndex (siIndex=%d)\n",
               (long)(now - lastReset), sens->getSiIndex());
      } else {
        LOGAR("resetSiIndex()");
        sens->resetSiIndex();
      }
    }
  }
  return reinterpret_cast<AlgorithmContext *>(jalg);
}
#endif

static bool vers(getNativefunctions)() { return true; }
/*static bool vers(getNativefunctions)() {
   if constexpr (vers(0)==02)   {
        return true;
        }
   else {
        std::string_view alglib=algLibName;
        void *handle=openlib(alglib);
        if(!handle) {
            LOGGER("dlopen %s failed: %s\n",alglib.data(),dlerror());
            return false;
            }
         const char *getjsonstr=jsonname(get,Ev);
        vers(getjson)= (getjson_t)dlsym(handle,getjsonstr);
         if(!vers(getjson)) {
             LOGGER("dlsym %s failed\n",getjsonstr);
            return false;
             }
         const char *setjsonstr=jsonname(set,EPc);
        vers(setjson)= (setjson_t)dlsym(handle,setjsonstr);
         if(!vers(setjson)) {
             LOGGER("dlsym %s failed\n",setjsonstr);
            return false;
             }
         LOGAR("found Nativefunctions");
         return true;
         }
    } */
