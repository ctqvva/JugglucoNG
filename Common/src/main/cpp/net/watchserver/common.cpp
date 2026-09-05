#include <stdio.h>
#include <time.h>
#include <charconv>
#include <stdint.h>
#include <system_error>
#include <inttypes.h>
#include <cmath>
#include <jni.h>

#include "SensorGlucoseData.hpp"
#include "gltype.hpp"
#include "common.hpp"
#include "logs.hpp"
#include "nightnumcategories.hpp"
extern JNIEnv *getenv();
extern jclass JNINightscoutCalibration;
extern double getdelta(float change);
extern std::string_view getdeltaname(float rate);
extern double calibrateONE(const SensorGlucoseData *sens,const ScanData &value);
extern double calibrateONE(const SensorGlucoseData *sens,const uint32_t time,const double value);

static jclass exportvalueclass=nullptr;

static bool ensureexportvalueclass(JNIEnv *env) {
    if(exportvalueclass!=nullptr)
        return true;
    if(JNINightscoutCalibration!=nullptr) {
        exportvalueclass=JNINightscoutCalibration;
        return true;
        }
    constexpr const char calibrationclassstr[]="tk/glucodata/NightscoutCalibration";
    if(jclass cl=env->FindClass(calibrationclassstr)) {
        exportvalueclass=(jclass)env->NewGlobalRef(cl);
        env->DeleteLocalRef(cl);
        return exportvalueclass!=nullptr;
        }
    if(env->ExceptionCheck())
        env->ExceptionClear();
    LOGGER("FindClass(%s) failed\n",calibrationclassstr);
    return false;
    }

//Value-based entry point, for outputs whose records are not ScanData: the LibreView
//history stream carries Glucose records, which live outside the polls array and so have
//no raw counterpart to look up. Those callers pass rawCurrent=0, which leaves a
//raw-primary sensor on its auto lane rather than calibrating against a value that is not
//there.
int resolveExportedMgdlAt(const SensorGlucoseData *sens,const sensorname_t *sensorname,
                          const uint32_t tim,const int storedMgdl,const int rawCurrent) {
    if(!sens||!sensorname||storedMgdl<=0)
        return 0;
    int autoMgdl=storedMgdl;
    if(settings->data()->DoCalibrate) {
        if(double calibrated=calibrateONE(sens,tim,(double)storedMgdl);!isnan(calibrated))
            autoMgdl=(int)round(calibrated);
        }
    auto env=getenv();
    if(env==nullptr||!ensureexportvalueclass(env))
        return autoMgdl;
    const static jmethodID exportedValueMethod = env->GetStaticMethodID(
        exportvalueclass,
        "resolveExportedValueMgdl",
        "(Ljava/lang/String;IIIJ)I"
    );
    if(exportedValueMethod==nullptr) {
        if(env->ExceptionCheck())
            env->ExceptionClear();
        return autoMgdl;
        }
    char sensornameStr[64];
    copyfixedsensorname(sensornameStr,sizeof(sensornameStr),sensorname);
    auto jsensor=env->NewStringUTF(sensornameStr);
    const auto *info=sens->getinfo();
    const int viewMode=info?info->viewMode:0;
    const int resolved=env->CallStaticIntMethod(
        exportvalueclass,
        exportedValueMethod,
        jsensor,
        viewMode,
        autoMgdl,
        rawCurrent,
        (jlong)tim*1000LL
    );
    env->DeleteLocalRef(jsensor);
    if(env->ExceptionCheck()) {
        env->ExceptionClear();
        return autoMgdl;
        }
    return resolved>0?resolved:autoMgdl;
    }

int resolveExportedMgdl(const SensorGlucoseData *sens, const ScanData *val,
                        const sensorname_t *sensorname) {
    if(!sens||!val||!sensorname)
        return 0;
    return resolveExportedMgdlAt(sens,sensorname,val->gettime(),val->getmgdL(),
                                 sens->getRawForPoll(val));
    }

const ScanData *makeExportedScan(const SensorGlucoseData *sens,
                                 const ScanData *val,
                                 const sensorname_t *sensorname,
                                 ScanData &storage) {
    if(!val)
        return nullptr;
    storage=*val;
    const int mgdL=resolveExportedMgdl(sens,val,sensorname);
    if(mgdL>0)
        storage.g=mgdL;
    return &storage;
    }

int getExchangeSmoothingSeconds() {
    auto env=getenv();
    if(env==nullptr||!ensureexportvalueclass(env))
        return 0;
    const static jmethodID smoothingMethod = env->GetStaticMethodID(
        exportvalueclass,
        "getExchangeSmoothingSeconds",
        "()I"
    );
    if(smoothingMethod==nullptr) {
        if(env->ExceptionCheck())
            env->ExceptionClear();
        return 0;
        }
    const int seconds=env->CallStaticIntMethod(exportvalueclass,smoothingMethod);
    if(env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
        }
    return seconds>0?seconds:0;
    }

int getExchangeOutputIntervalSeconds() {
    auto env=getenv();
    if(env==nullptr||!ensureexportvalueclass(env))
        return 0;
    const static jmethodID intervalMethod = env->GetStaticMethodID(
        exportvalueclass,
        "getExchangeOutputIntervalSeconds",
        "()I"
    );
    if(intervalMethod==nullptr) {
        if(env->ExceptionCheck())
            env->ExceptionClear();
        return 0;
        }
    const int seconds=env->CallStaticIntMethod(exportvalueclass,intervalMethod);
    if(env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
        }
    return seconds>0?seconds:0;
    }
int mkv3streamid(char *outiter,const sensorname_t *name,int num) { 
//LOGGER("sensorname=%s\n",name->data());
const uint16_t *gets=reinterpret_cast<const uint16_t*>(name->data());
int ch2=name->back();
int len=sprintf(outiter,"%04x%04x-%04x-%04x-%02xee-eeeeeeeeeeee",(int)(gets[4]),(int)(gets[3]),(int)(gets[2]),(int)(gets[1]),ch2);

outiter+=24;
if(auto [ptr,ec]=std::to_chars(outiter,outiter+10,num);ec != std::errc()) {
	LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
	} 
//puts(buf);
return len;
//0fffffff-ffff-ffff-ffff-17500fffffff"
//c149bacfb000007f
}
int mkv1streamid(char *outiter,const sensorname_t *name,int num) { 
//LOGGER("sensorname=%s\n",name->data());
const uint16_t *gets=reinterpret_cast<const uint16_t*>(name->data());
int ch2=name->back();
int len=sprintf(outiter,"%04x%04x%04x%04x%02xeeeeee",(int)(gets[4]),(int)(gets[3]),(int)(gets[2]),(int)(gets[1]),ch2);

outiter+=18;
if(auto [ptr,ec]=std::to_chars(outiter,outiter+10,num);ec != std::errc()) {
	LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
	} 
//puts(buf);

return len;
//0fffffff-ffff-ffff-ffff-17500fffffff"
//c149bacfb000007f
}
char * writev3entry(char *outin,const ScanData *val, const sensorname_t *sensorname,bool server) {
	char *outptr=outin;
	addar(outptr,R"({"app":"Juggluco","device":")");
	memcpy(outptr,sensorname->data(),sensorname->size());
	outptr+=sensorname->size();
	addar(outptr,R"(","date":)");
	const time_t tim=val->gettime();
	if(auto [ptr,ec]=std::to_chars(outptr,outptr+12,tim);ec == std::errc()) {
		outptr=ptr;
	 	}
	else {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
	addar(outptr,R"(000,"sgv":)");
    int mgdL=val->getmgdL();
	if(auto [ptr,ec]=std::to_chars(outptr,outptr+12,mgdL);ec == std::errc()) {
		outptr=ptr;
	 	}
	else {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
	addar(outptr,R"(,"delta":)");
	 double delta= getdelta(val->ch);

#if __NDK_MAJOR__ >= 26
	if(auto [ptr,ec]=std::to_chars(outptr,outptr+20,delta,std::chars_format::fixed,3);ec == std::errc()) {
		outptr=ptr;
	 	}
	else {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
#else
	outptr+=sprintf(outptr,"%.3f",delta);
#endif
	addar(outptr,R"(,"direction":")");
	 std::string_view name=getdeltaname(val->ch);
	 addstrview(outptr,name);
	addar(outptr,R"(","type":"sgv")");
	if(server)
		addar(outptr,R"(,"utcOffset":0)");
	addar(outptr,R"(,"identifier":")");
	outptr+=mkv3streamid(outptr,sensorname,val->id);
	if(server) {
		addar(outptr,R"(","created_at":")");
		struct tm tmbuf;
		gmtime_r(&tim, &tmbuf);
		outptr+=sprintf(outptr,R"(%04d-%02d-%02dT%02d:%02d:%02d)",tmbuf.tm_year+1900,tmbuf.tm_mon+1,tmbuf.tm_mday, tmbuf.tm_hour, tmbuf.tm_min,tmbuf.tm_sec);

		addar(outptr,R"(.000Z","srvModified":)");
		if(auto [ptr,ec]=std::to_chars(outptr,outptr+12,tim);ec == std::errc()) {
			outptr=ptr;
			}
		else {
			LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
			}

		addar(outptr,R"(000,"srvCreated":)");
		if(auto [ptr,ec]=std::to_chars(outptr,outptr+12,tim);ec == std::errc()) {
			outptr=ptr;
			}
		else {
			LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
			}
		addar(outptr,R"(000})");
		}
	else {
		addar(outptr,R"(","_id":")");
		outptr+=mkv1streamid(outptr,sensorname,val->id);
		addar(outptr,R"("})");
		}
	return outptr;
	}

int mkid(char *outiter,int base,int pos) {
	int len=sprintf(outiter,"ba%de%d",base,pos);
	int over=24-len;
	memset(outiter+len,'b',over);
	return 24;
	}

int mkidid(char *outiter,int base,int pos) {
	int len=sprintf(outiter,"%u%07u%016" PRIx64  ,base,pos,settings->data()->jugglucoID);
	return len;
	}

int mkididV3(char *input,int base,int pos) {
	char *outiter=input;
	constexpr const  char temp[]="ffffffffffff";
	memcpy(outiter,temp,4);
	if(auto [ptr,ec]=std::to_chars(outiter,outiter+10,base);ec != std::errc()) {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
	outiter+=4;
	uint16_t *id=reinterpret_cast<uint16_t *>(&settings->data()->jugglucoID);
	outiter+=sprintf(outiter,"%04x-%04x-%04x-%04x-",id[0],id[1],id[2],id[3]);
	memcpy(outiter,temp,sizeof(temp)-1);
	if(auto [ptr,ec]=std::to_chars(outiter,outiter+10,pos);ec != std::errc()) {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
	return 36;
	}



int mkidV3(char *outiter,int base,int pos) {
	constexpr const  char temp[]="ffffffff-ffff-ffff-ffff-ffffffffffff";
	memcpy(outiter,temp,sizeof(temp));
	if(auto [ptr,ec]=std::to_chars(outiter,outiter+10,base);ec != std::errc()) {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
	outiter+=24;
	if(auto [ptr,ec]=std::to_chars(outiter,outiter+10,pos);ec != std::errc()) {
		LOGGER("tochar failed: %s\n",std::make_error_code(ec).message().c_str());
		}
	return sizeof(temp)-1;
	}
#include "nums/num.h"
extern char *writetreatment(char *outiter,const int numbase,const int pos,const Num*num,int border,int borderID);
char *writetreatment(char *outiter,const int numbase,const int pos,const Num*num,int border,int borderID) {
	const int type=num->type;
	if(type<0||type>=settings->varcount()||!settings->data()->Nightnums[type].kind) {
		return outiter;
		}
	const time_t tim=num->gettime();
        struct tm tmbuf;
        gmtime_r(&tim, &tmbuf);
		addar(outiter,R"({"_id":")");
	if(pos>=borderID) {
		outiter+=mkidid(outiter,numbase,pos);
		}
	else {
		if(pos>=border) 
			outiter+=mkid(outiter,numbase,pos);
		else
			outiter+=sprintf(outiter,"num%d#%d",numbase,pos);
		}


	outiter+=sprintf(outiter,R"(","date":%lu)",tim);
	addar(outiter,R"(000,"eventType":"<none>","enteredBy":"Juggluco","created_at":")");
	outiter+=sprintf(outiter,R"(%04d-%02d-%02dT%02d:%02d:%02d.000Z",)",tmbuf.tm_year+1900,tmbuf.tm_mon+1,tmbuf.tm_mday, tmbuf.tm_hour, tmbuf.tm_min,tmbuf.tm_sec);

	float w=0.0f;
	 if((w=longNightWeight(type))!=0.0f) {
	 	
	 	addar(outiter,R"("notes":"Long-Acting",)");
	 	}
	else { if((w=rapidNightWeight(type))!=0.0f) {
	 	addar(outiter,R"("notes":"Rapid-Acting",)");
	 	}
		}
	if(w!=0.0f) {
		const char * typestr=settings->getlabel(type).data();;
		auto units=w*num->value;
		outiter+=sprintf(outiter,R"("carbs":null,"insulin":%g,"insulinType":"%s"},)",units,typestr);

		}
	else {
		if((w=carboNightWeight(type) )!=0.0f) {
			outiter+=sprintf(outiter,R"("carbs":%g,"insulin":null},)",w*num->value);
			}
		else {
			std::string_view typestr=settings->getlabel(type);
			outiter+=sprintf(outiter,R"("notes":"%s %g","carbs":null,"insulin":null},)",typestr.data(),num->value);
			}
		}
	return outiter;
	}
