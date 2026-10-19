
#pragma once
#include "../accu/shortfloat.hpp"
#include "MeterTime.hpp"
constexpr const int MMOLL_TO_MGDL=18;
struct Rest {
    shortfloat glucose;
    int8_t sampleType:4;
    int8_t sampleLocation:4;
    int8_t status;
    };
struct MeterData {
     bool timeOffsetPresent:1;
     bool typeAndLocationPresent:1;
     bool microMolperL:1;
     bool sensorStatusAnnunciationPresent:1;
     bool contextInfoFollows:1;
     uint8_t restflags:3;
     int16_t  index;
     MeterTime time;
     union {
        struct {
           int16_t offset;
           Rest  rest1;
           };
       Rest rest2;
       };
private:
float inmgdL(shortfloat in) const {
    if(microMolperL) {
        return in.getvalue()*1000.0f* MMOLL_TO_MGDL;
        }
     return in.getvalue()*100000.0f;
    }
public:
    int16_t getTimeoffset() const {
        if(timeOffsetPresent)
            return offset;
        return 0;
        }
     float getmgdL() const {
        if(timeOffsetPresent) 
            return inmgdL(rest1.glucose);
        return inmgdL(rest2.glucose);
        }
     }  __attribute__ ((packed)) ;


        
