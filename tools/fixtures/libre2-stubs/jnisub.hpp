#pragma once
#include <jni.h>
#include <vector>
#include "scanstate.hpp"
#include "glucose.hpp"
struct jniHistory : std::vector<GlucoseValue *> {};
struct AlgorithmResults {
    const jniHistory *hist;
    const GlucoseNow *glu;
    const int alarm;
    const jboolean isActionable;
    const int esaMinutesToWait;
    const jboolean lsaDetected;
    bool inserted, removed;
    ~AlgorithmResults() { for(auto *v : *hist) delete v; delete hist; delete glu; }
};
