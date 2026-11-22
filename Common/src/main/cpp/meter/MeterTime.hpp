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
// Date Time fields may be 0 for "unknown", and mktime() would quietly turn such a
// date (or 31 February) into another real one; reject anything not on the calendar.
bool valid() const {
    if(year<1582||year>9999||month<1||month>12||day<1||hour<0||hour>23||minute<0||minute>59||second<0||second>59)
        return false;
    constexpr const int8_t monthDays[]{31,28,31,30,31,30,31,31,30,31,30,31};
    const bool leap=(year%4==0&&year%100!=0)||year%400==0;
    return day<=monthDays[month-1]+(month==2&&leap);
    }
time_t getGMT() const {
     auto tmbuf=gettm();
    return timegm(&tmbuf);
    }
time_t  getLocaltime() const {
     auto tmbuf=gettm();
    return mktime(&tmbuf);
    }
}__attribute__ ((packed)) ;

