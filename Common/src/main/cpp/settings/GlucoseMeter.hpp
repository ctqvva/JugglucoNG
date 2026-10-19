#pragma once
#include <stdint.h>
constexpr const int maxglucosemeters=40;
constexpr const int maxDeviceName=39;
struct GlucoseMeter {
        int32_t  timeoffset;
        uint32_t  lastTime;
        uint16_t nextIndex;
        uint8_t deviceAddress[6];
        int8_t reserved1:7;
        bool active:1;
        char deviceName[maxDeviceName];
        uint64_t reserved3[2];
        };
