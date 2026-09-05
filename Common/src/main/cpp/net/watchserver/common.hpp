#pragma once
#include "SensorGlucoseData.hpp"
#include "../../exchangetrend.hpp"
#include "gltype.hpp"
#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstring>
#include <span>
#include <string_view>
#include <time.h>
template <typename T>
inline void	addstrview(char *&uitptr,const T indata) {
	memcpy(uitptr,indata.data(),indata.size());
	uitptr+=indata.size();
	}

template <class T, size_t N>
inline static constexpr void addar(char *&uitptr,const T (&array)[N]) {
	constexpr const int len=N-1;
	memcpy(uitptr,array,len);
	uitptr+=len;
	}

inline double getdelta(float change) {
	static constexpr const double deltatimes=5.0;
	 return isnan(change)?0:change*deltatimes; //json has no nan. This is obviously wrong, I don't know what else to do. Return null?
	 }

inline std::string_view fixedsensorview(const sensorname_t *sensorname) {
	if(!sensorname)
		return {};
	const char *name=sensorname->data();
	if(name[0]=='X'&&name[1]=='-') {
		size_t len=0;
		for(;len<32;len++) {
			const unsigned char ch=static_cast<unsigned char>(name[len]);
			if(ch==0||ch<0x20||ch=='/'||ch=='\\'||ch=='"')
				break;
			}
		if(len>=sensorname->size())
			return {name,len};
		}
	size_t len=0;
	for(;len<sensorname->size();len++) {
		const unsigned char ch=static_cast<unsigned char>(name[len]);
		if(ch==0||ch<0x20||ch=='/'||ch=='\\'||ch=='"')
			break;
		}
	return {name,len};
	}

inline void copyfixedsensorname(char *out,size_t outsize,const sensorname_t *sensorname) {
	if(!outsize)
		return;
	const std::string_view name=fixedsensorview(sensorname);
	const size_t len=std::min(name.size(),outsize-1);
	memcpy(out,name.data(),len);
	out[len]='\0';
	}

inline void addjsonuint(char *&out,unsigned long long value) {
	char tmp[32];
	if(auto [ptr,ec]=std::to_chars(tmp,tmp+sizeof(tmp),value);ec==std::errc()) {
		const auto len=ptr-tmp;
		memcpy(out,tmp,len);
		out+=len;
		}
	}

inline void addjsonint(char *&out,long long value) {
	if(value<0) {
		*out++='-';
		addjsonuint(out,static_cast<unsigned long long>(-(value+1))+1ULL);
		}
	else {
		addjsonuint(out,static_cast<unsigned long long>(value));
		}
	}

inline void addjsonpadded2(char *&out,int value) {
	if(value<10)
		*out++='0';
	addjsonuint(out,static_cast<unsigned>(value));
	}

inline void addjsonpadded3(char *&out,int value) {
	if(value<100)
		*out++='0';
	if(value<10)
		*out++='0';
	addjsonuint(out,static_cast<unsigned>(value));
	}

inline void addjsonfixed3(char *&out,double value) {
	if(!std::isfinite(value))
		value=0.0;
	long long scaled=std::llround(value*1000.0);
	if(scaled<0) {
		*out++='-';
		scaled=-scaled;
		}
	addjsonint(out,scaled/1000);
	*out++='.';
	addjsonpadded3(out,static_cast<int>(scaled%1000));
	}

inline int addNightscoutDateString(char *&out,time_t tim) {
	char *start=out;
	struct tm tmbuf;
	int seczone=timegm(localtime_r(&tim,&tmbuf))-tim;
	int minutes=seczone/60;
	const bool neg=minutes<0;
	if(neg)
		minutes=-minutes;
	const int hours=minutes/60;
	const int minleft=minutes%60;
	addjsonint(out,tmbuf.tm_year+1900);
	*out++='-';
	addjsonpadded2(out,tmbuf.tm_mon+1);
	*out++='-';
	addjsonpadded2(out,tmbuf.tm_mday);
	*out++='T';
	addjsonpadded2(out,tmbuf.tm_hour);
	*out++=':';
	addjsonpadded2(out,tmbuf.tm_min);
	*out++=':';
	addjsonpadded2(out,tmbuf.tm_sec);
	addar(out,R"(.000)");
	*out++=neg?'-':'+';
	addjsonpadded2(out,hours);
	*out++=':';
	addjsonpadded2(out,minleft);
	return out-start;
	}

inline int addNightscoutDateStringGMT(char *&out,time_t tim) {
	char *start=out;
	struct tm tmbuf;
	gmtime_r(&tim,&tmbuf);
	addjsonint(out,tmbuf.tm_year+1900);
	*out++='-';
	addjsonpadded2(out,tmbuf.tm_mon+1);
	*out++='-';
	addjsonpadded2(out,tmbuf.tm_mday);
	*out++='T';
	addjsonpadded2(out,tmbuf.tm_hour);
	*out++=':';
	addjsonpadded2(out,tmbuf.tm_min);
	*out++=':';
	addjsonpadded2(out,tmbuf.tm_sec);
	addar(out,R"(.000Z)");
	return out-start;
	}

inline void addjsonescaped(char *&outptr,std::string_view value) {
	static constexpr char hex[]="0123456789abcdef";
	for(unsigned char ch: value) {
		switch(ch) {
			case '"':
				*outptr++='\\';
				*outptr++='"';
				break;
			case '\\':
				*outptr++='\\';
				*outptr++='\\';
				break;
			case '\b':
				*outptr++='\\';
				*outptr++='b';
				break;
			case '\f':
				*outptr++='\\';
				*outptr++='f';
				break;
			case '\n':
				*outptr++='\\';
				*outptr++='n';
				break;
			case '\r':
				*outptr++='\\';
				*outptr++='r';
				break;
			case '\t':
				*outptr++='\\';
				*outptr++='t';
				break;
			default:
				if(ch<0x20) {
					*outptr++='\\';
					*outptr++='u';
					*outptr++='0';
					*outptr++='0';
					*outptr++=hex[ch>>4];
					*outptr++=hex[ch&0x0F];
					}
				else {
					*outptr++=static_cast<char>(ch);
					}
			}
		}
	}

inline void addjsonstring(char *&outptr,std::string_view value) {
	*outptr++='"';
	addjsonescaped(outptr,value);
	*outptr++='"';
	}

int resolveExportedMgdl(const SensorGlucoseData *sens, const ScanData *val,
                        const sensorname_t *sensorname);
int resolveExportedMgdlAt(const SensorGlucoseData *sens, const sensorname_t *sensorname,
                          const uint32_t tim, const int storedMgdl, const int rawCurrent);
const ScanData *makeExportedScan(const SensorGlucoseData *sens,
                                 const ScanData *val,
                                 const sensorname_t *sensorname,
                                 ScanData &storage);
int getExchangeOutputIntervalSeconds();
int getExchangeSmoothingSeconds();
