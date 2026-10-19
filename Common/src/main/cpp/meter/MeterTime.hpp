#pragma once
#include <time.h>
struct MeterTime {
 int16_t year;
 int8_t  month;
 int8_t  day;
 int8_t  hour;
 int8_t  minute;
 int8_t  second;
struct tm gettm() const {
    struct tm tmbuf{
            .tm_sec=second,
            .tm_min=minute,
            .tm_hour=hour,
            .tm_mday=day,
            .tm_mon=month - 1,
            .tm_year=year - 1900,
            .tm_isdst=-1
    };
     return tmbuf;
     };
time_t getGMT() const {
     auto tmbuf=gettm();
    return timegm(&tmbuf);
    }
time_t  getLocaltime() const {
     auto tmbuf=gettm();
    return mktime(&tmbuf);
    }
}__attribute__ ((packed)) ;

