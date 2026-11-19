/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Tue Apr 30 15:11:24 CEST 2024                                                 */

#define setthreadname(buf) prctl(PR_SET_NAME, buf, 0, 0, 0)

#include <string.h>
#include <jni.h>
#include <assert.h>
#include <filesystem>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <dlfcn.h>
#include <link.h>
#include <string_view>
#include "SensorGlucoseData.hpp"
#include "debugclone.hpp"
#include "settings/settings.hpp"
#include "libre2.hpp"
#include "jnisubin.hpp"
#include "hexstr.hpp"
#if !defined(__aarch64__)        //||!defined(NOLOG)
#define GEN2ROOTSHECK 1
#endif
//constexpr const int librewearduration=14*24*60;

typedef struct JNINativeInterface * JNIEnvC;
extern struct JNINativeInterface envbuf;
extern JavaVM *getnewvm();

extern "C" jint         subRegisterNatives(JNIEnv*, jclass name, const JNINativeMethod*methods, jint nr);
#define VISIBLE __attribute__((__visibility__("default")))
#include "fromjava.h"
// subtrace.h's emulated JNIEnv routes RegisterNatives here. Only the removed vendor
// library registered natives through it.
jint         subRegisterNatives(JNIEnv*, jclass, const JNINativeMethod*, jint nr) {
    LOGGER("RegisterNatives(%d) ignored\n",nr);
    return 0;
    }
jint parsertype=1095774808;

// Libre 1/2 glucose, payloads and patch metadata come from the open libre2_imp backend.
#include "libre2_imp/backend.hpp"

extern string  encodeStatusCode(jlong j);
int gettimezoneoffset(const time_t *timp) {
     struct tm tmbuf;
     return timegm(localtime_r(timp,&tmbuf)) - *timp;
    }
data_t *fromjbyteArray(JNIEnv *env,jbyteArray jar,jint len) {
     jsize lens=env->GetArrayLength(jar);
     if(len>=0&&lens>len)
         lens=len;
     data_t *dat=data_t::newex(lens);
    env->GetByteArrayRegion(jar, 0, lens, dat->data());
    LOGGER("fromjbyteArray %d\n",lens);
    return dat;
    }

//timevalues     patchtimevalues(const data_t *info) {
static jint getFamily(const data_t *info) {
    const jint fam=libre2_imp::family(subenv,nullptr, parsertype, (jbyteArray) info,nullptr);
    LOGGER("getProductFamily()=%d\n", fam);
    return fam;
    }


Abbott::Abbott(JNIEnv *env,string_view basedir,jbyteArray juid, const data_t *info): Abbott(basedir,fromjbyteArray(env,juid),getFamily(info)) {
    if(hist&&hist->getinfo()->wearduration>(60*7*24)) {
        warmup=hist->getinfo()->warmup;
        wearduration=hist->getinfo()->wearduration;
        }
    else {
        timevalues times= patchtimevalues(info) ;
        warmup=times.warmup;
        wearduration=times.wear;
      if(hist) {
           hist->getinfo()->warmup=warmup;
           hist->getinfo()->wearduration=wearduration;
          }
        }
    }
extern jclasser AlarmC, NonActiona, GluRange, attconf;
AlgorithmResults *callAbbottAlg(data_t *uid,int startsincebase,scanstate *oldstate,scandata *data,scanstate *newstate,outobj *starttime,outobj *endtime,jobject person,int warmup,int wear) {
    const unsigned known_start=startsincebase>0?unsigned(startsincebase):0;
    return libre2_imp::scan(uid,data->info,data->data,oldstate,newstate,unsigned(data->gettime()-basesecs),starttime,endtime,warmup,known_start);
    }

//AlgorithmResults *callAbbottAlg(data_t *uid,int startsincebase,scanstate *oldstate,scandata *data,scanstate *newstate,outobj *starttime,outobj *endtime,jobject person) {
AlgorithmResults *Abbott::callAbbottAlg(int startsincebase,scanstate *oldstate,scandata *data,scanstate *newstate,outobj *starttime,outobj *endtime,jobject person) {

    return ::callAbbottAlg(uid,startsincebase,oldstate,data,newstate,starttime,endtime,person,warmup,wearduration) ;
    }
//extern bool savehistory(AlgorithmResults *res,time_t nutime,nfcdata &nfc,SensorGlucoseData &save) ;
//nfcdata  *lastnfcdata=nullptr;
//extern bool savehistory(const AlgorithmResults *res,time_t nutime,const nfcdata *nfcptr,SensorGlucoseData &save) ;
extern void setusedsensors() ;




extern     bool    setbluetoothon;
Abbott::scanresult_t Abbott::callAbbottAlg(scandata *data) {
//    const int sensorindex=sensors->sensorindex(serial.data());
    if(sensorindex<0)
        return {nullptr,nullptr};
        
    if(!hist&&!(hist=sensors->getSensorData(sensorindex)))  {
        LOGGER("getSensorData(%s) returns null\n", serial.data());
        return {nullptr,nullptr};
        }
    scanstate *newstate=new scanstate(sensordir,data->gettime());

    time_t nutime=data->gettime();

    time_t startDate=hist->getstarttime();

#ifndef NOLOG
    char timebuf[30];

    LOGGER("startdate: %s",ctime_r(&startDate,timebuf)?timebuf:"null");
#endif
    int startsincebase=startDate-basesecs;
//    const int timelast=nutime-startDate; LOGGER("timelast=%d endsensorsecs=%d\n",timelast,endsensorsecs);
const    int timeleft=hist->officialendtime()-nutime;
LOGGER("timeleft=%d\n",timeleft); 
    outobj person,starttime,endtime;
    AlgorithmResults *res=callAbbottAlg(startsincebase,state,data,newstate,&starttime,&endtime,person);
    if(res) {
        time_t newnu=basesecs+reinterpret_cast<intptr_t>(endtime.ptr);
        if(res->currentglucose().getValue())
            hist->waiting=false;
        else
            hist->waiting=true;
        decltype(auto) gl=res->currentglucose();
        int gid=gl.getId();
        decltype(auto) reshist=res->history();
extern bool saveSputnik_PG2(const jniHistory &hist,time_t nutime,int nuid,const nfcdata  *nfcptr, SensorGlucoseData &save) ;
        saveSputnik_PG2(reshist,newnu,gid, data, *hist);
extern int saveScanTrend(const nfcdata *nfcptr,time_t nutime,int nuid, SensorGlucoseData &save) ;
        /*The patch also holds the 16 one-minute samples before the scan; recover the ones
          the BLE stream missed instead of leaving them in trends.dat.*/
        saveScanTrend(data,newnu,gid, *hist);
        sensor *senso=sensors->getsensor(sensorindex);
        if(newnu<(nutime-60*3)) {
//            lastscan=nullptr;
            LOGGER("senso->finished was %d, set to 1. %s: newnu=%zd nutime=%zd\n",senso->finished,serial.data(),newnu,nutime);
            senso->markFinished();
            extern void    setstreaming(SensorGlucoseData *hist);
            setstreaming(hist);
            void setusedsensors() ;
            setusedsensors();
            }
        else {
            LOGGER("senso->finished was %d, set to 0\n",senso->finished);
        //    senso->finished=0;
            hist->saveglucose(data,newnu,gid,gl.getValue(),res->trend(),res->RateOfChange()); 
            auto last= hist->getScandata();
//            lastscan=&last.back();

            newstate->makelink();
            if(state) state->removefile();
            delete state;
            state=newstate;
            return {res,&last.back()};
            }

        }
    else
        hist->waiting=true;
    delete newstate; 
    return {res,nullptr};
    }
Abbott::scanresult_t Abbott::ProcessOne(scandata *data) {
    // A known sensor rebuilds a missing or unusable state from NFC and the saved start time.
    // A first scan initializes below.
    if(state&&!state->good()) {
        delete state;
        state=nullptr;
        }
    if(hist&&hist->getstarttime()>basesecs)return callAbbottAlg(data);
    if(!state||!state->good()) {
        delete state;
        state=initnewsensor( data) ;
        if(!state)  
            return {nullptr,nullptr};
            
        else 
            {
            return { Initialized,nullptr}; 
            }
        }
    return callAbbottAlg(data);
    }

//const data_t*  getStreamingPayload(const data_t * sensorident, const data_t * patchinfo, uint32_t tim) ;
//scanstate is a bad idea, I should change it to malloc e.d.
const data_t*  getStreamingPayload(scanstate *stateptr,const data_t * sensorident, const data_t * patchinfo, uint32_t tim) ;
scanstate *Abbott::initnewsensor( scandata *data) {
    LOGAR("initnewsensor");
    jbyteArray juid =(jbyteArray) uid; 
    scanstate inp,newstate(4);
    outobj person,endtime,starttime;
    AlgorithmResults * alg=callAbbottAlg(0,&inp,data,&newstate,&starttime,&endtime,person);
    if(!alg) {
        return nullptr;
        }
    jint startminbase=starttime.toint();
    time_t start=basesecs+startminbase;

    timevalues times= patchtimevalues(data->info) ;

    if(int sindex=sensors->sensorindex(serial.data());sindex>=0) {
        sensorindex=sindex;
         hist=sensors->getSensorData(sindex);

        if(hist->streamingIsEnabled()==1)
            hist->setbluetoothOn(0);
        }
    else {
        const char *infodata=(const char *)data->info->data();
        uint32_t bluestart;
        scanstate astate;
        if(getStreamingPayload(&astate,uid, data->info, start)) {
                bluestart=0;
                }
            else
                bluestart=SensorGlucoseData:: bluestartunknown;
        SensorGlucoseData::mkdatabase(sensordir,start,(const char *)uid->data(),infodata,&times,3,bluestart,nullptr) ;
        int ind=sensors->addsensor(serial.data());

        hist=sensors->getSensorData(ind);
        sensorindex=ind;
extern bool streamHistory() ;

        void setstartedwithStreamhistory();
        if(streamHistory()) 
            setstartedwithStreamhistory();
        }
   sensor *sen=sensors->getsensor(sensorindex);
   sen->halfdays=times.wear/(30*24)+1;

    constexpr const int    threeyear=94694400 ;//2013
    constexpr const int sec_per_day=60*60*24;
    scanstate *newstateptr=new scanstate(sensordir,data->gettime());
    jnidata_t  hierjnidata={&envbuf,newstateptr};
    JNIEnv *hiersubenv=(JNIEnv *) &hierjnidata;

    data_t *messuit;


    LOGSTRING("voor activationComplete ");
    if( (messuit=reinterpret_cast<data_t *>( libre2_imp::activate_state(hiersubenv,nullptr,juid, (startminbase > threeyear ? startminbase - threeyear : 0) / sec_per_day, startminbase, (jbyteArray)newstate.get(MESS),nullptr)))&&messuit->length()>0) {
        LOGSTRING(" activationComplete>0\n");
        newstateptr->makenull(COMP);    //NODIG?
        newstateptr->makenull(ATTE);    
        }
    else {
        newstateptr->reset();
        data_t *messwas=newstate.get(MESS);
        messuit=newstateptr->alloc(messwas->length());
        memcpy(messuit->data(),messwas->data(),messwas->length());
        }
    delete alg;
    newstateptr->makelink();
    newstateptr->setpos(MESS,messuit);
    return newstateptr;
    }


extern std::string_view libdirname;
// Directory of the app's native libraries; set from Java (javacurve.cpp). Libre 3's legacy loader uses it.
std::string_view libdirname;
// Set by the removed vendor initialisation; share/hooks.cpp still reads it.
bool doinitnative=false;
// Libre 3's legacy library sets this; the Libre 2 stream reads it for its wake lock.
bool rootcheck=false;

static AlgorithmResults * processBlue(data_t * bluedata, jbyte person,

const data_t *ident,
#ifdef HASP1
const data_t *info,
#endif
scanstate *state,
scanstate *newstate,uint32_t startsincebase,uint32_t nutime,int warmup,int wearduration) ;


timevalues     patchtimevalues(const data_t *info) {
    outobj warmup, wear;
  if(libre2_imp::times(subenv,nullptr,parsertype, (jbyteArray) info, warmup, wear,nullptr)) {
      const auto warmupget=(uint16_t)warmup.toint();
      const auto wearget=(uint16_t)wear.toint();
      LOGGER("patchtimevalues warmup=%d wear=%d\n",warmupget,wearget);
        return  {warmupget, wearget};
            }
     LOGAR("patchtimevalues failed");
     return {0,0};
    }
jbyte  getactivationcommand(const data_t *info) {
    LOGSTRING("start getActivationCommand ");
        jbyte res= libre2_imp::activation_command(subenv,nullptr, parsertype, (jbyteArray) info,nullptr);
    LOGSTRING(" end getActivationCommand\n");
        return res;
        }
extern "C" JNIEXPORT jboolean JNICALL fromjava(hasRootcheck)(JNIEnv* env, jclass obj) {
    return rootcheck;
    }
extern "C" JNIEXPORT jbyte JNICALL fromjava(activationcommand)(JNIEnv* env, jclass obj,jbyteArray info) {
    LOGAR("start getActivationCommand");
   jbyte res=  libre2_imp::activation_command(env,obj, parsertype, info,nullptr);
    LOGAR(" end getActivationCommand");
    return res;
    }
extern "C" JNIEXPORT jbyteArray  JNICALL fromjava(activationpayload)(JNIEnv *env, jclass obj,  jbyteArray patchid, jbyteArray info, jbyte person)  {
    LOGSTRING("start getActivationPayload ");
    jbyteArray res=   libre2_imp::activation_payload(env, obj,  parsertype,  patchid,  info, person,nullptr) ;
    LOGSTRING("end getActivationPayload\n");
    return res;
        }

bool streamHistory() {
    return true;
    }

//info and startsincebase should be the same as used by getEnableStreamingPayload
#define MODTIME
data_t * unlockKeySensor( SensorGlucoseData *usedhist,scanstate *stateptr) {
    jbyte person=0;
const auto [bluestart,info]= usedhist->getbluedata();

    jint startsincebase= bluestart-basesecs; 
jnidata_t  hierjnidata={&envbuf,stateptr};
JNIEnv *hiersubenv=(JNIEnv *) &hierjnidata;
    auto lock= usedhist->nextlock();
    LOGGER("start getStreamingUnlockPayload(env,null,%d,%s,%s,%d,%d,%d,token)\n",parsertype,hexstr(usedhist->getsensorident()).str(),hexstr(info).str(),person,startsincebase,lock);
    data_t *res= reinterpret_cast<data_t *>(libre2_imp::unlock_payload(hiersubenv,nullptr, parsertype, (jbyteArray)usedhist->getsensorident(), (jbyteArray)info, person, startsincebase, lock,nullptr));
    LOGAR(" end getStreamingUnlockPayload");
    return res;
    }
static AlgorithmResults * processBlue(data_t * bluedata, jbyte person,

const data_t *ident,
#ifdef HASP1
const data_t *info,
#endif
scanstate *state,
scanstate *newstate,uint32_t startsincebase,uint32_t nutime,int warmup,int wear) {
    return libre2_imp::stream(ident,
#ifdef HASP1
        info,
#else
        nullptr,
#endif
        bluedata,state,newstate,unsigned(nutime-basesecs),startsincebase);
    }


//extern std::span<streamdat> laststream;

extern bool addStreamHistory(const jniHistory &hist,time_t nutime,int nuid, SensorGlucoseData &save) ;

const AlgorithmResults *  libre2stream::processTooth(data_t * bluedata, scanstate *newstate,uint32_t nutime) {
hist->lastPacketTime = nutime;
const data_t *ident=getident();
#ifdef HASP1
const data_t *info=getinfo();
#endif

// An NFC scan may have rebuilt the saved state since this stream loaded its copy.
if(!state||!state->good()||!libre2_imp::usable_state(state,info)) {
    scanstate *fresh=locknew<scanstate>(hist->mutex,hist->getsensordir());
    if(!fresh||!fresh->good()||!libre2_imp::usable_state(fresh,info)) {
        delete fresh;
        return nullptr;
        }
    delete state;
    state=fresh;
    }
if(hist->getinfo()->wearduration<(7*24*60)) {
     auto [warmup,wear]= patchtimevalues(info) ;
   hist->getinfo()->warmup=warmup;
   hist->getinfo()->wearduration=wear;
   }
const AlgorithmResults * alg= processBlue(bluedata, person,

ident,
#ifdef HASP1
info,
#endif
state,
newstate, startsincebase,nutime,hist->getinfo()->warmup,hist->getinfo()->wearduration);
if(alg) {
        /*int insert=reinterpret_cast<intptr_t>(confinsert.ptr);
        int isremoved=reinterpret_cast<intptr_t>(removed.ptr);
        LOGGER("Is %sinserted and %sremoved\n",insert?"":"not ",isremoved?"":"not ");*/

//        AlgorithmResults *alg=(AlgorithmResults *)outalgres.ptr;
        const GlucoseNow & gl=alg->currentglucose();
        const int gluc=gl.getValue();
        const int qual=gl.getQuality();
        int gid=gl.getId();
        LOGGER("processStream %d %d %.1f\n",gid,qual,gluc/convfactordL);
        if(!qual&&gluc) {
            hist->clearSensorError();
            if(hist->savepoll(nutime,gid,gluc,gl.trend(),gl.rate())) {
                if(streamHistory()) {
                         addStreamHistory(alg->history(), nutime,gid, *hist) ;
                    }
                return alg;
                }
            else {
                delete alg;
                return ALGDUP_VALUE;

                }
            }
        else {
            delete alg;
            LOGSTRING("bad quality\n");
            LOGGER("bad quality quality=%d value=%d\n", qual, gluc);
            hist->setSensorError(nutime, qual);
            }
        }
return nullptr;
}


//WAS_JNIEXPORT jbyteArray JNICALL abbottdec(getEnableStreamingPayload)(JNIEnv *env, jobject obj, jint parsertype, jbyteArray sensorident, jbyteArray info, jbyte person, jint numinbase) { }

const data_t*  getStreamingPayload(scanstate *stateptr,const data_t * sensorident, const data_t * patchinfo, uint32_t tim) {
    jint timeminbase=tim-basesecs;
    jbyte person=0;
jnidata_t  hierjnidata={&envbuf,stateptr};
JNIEnv *hiersubenv=(JNIEnv *) &hierjnidata;
//    basismap.reset(); reinterpret_cast<jnidata_t *>(subenv)->map=&basismap;
    LOGSTRING("start getStreamingPayload ");
    const data_t *pay= reinterpret_cast<data_t *>(libre2_imp::enable_payload(hiersubenv,nullptr, parsertype, (jbyteArray)sensorident, (jbyteArray)patchinfo, person, timeminbase,nullptr));
    LOGSTRING(" end getStreamingPayload\n");
    return pay;
    }
extern "C" JNIEXPORT jbyteArray  JNICALL fromjava(bluetoothOnKey)(JNIEnv *envin, jclass cl,jbyteArray sensorident, jbyteArray patchinfo) {

      setthreadname( "bluetoothOnKey");
    int fam= libre2_imp::family(envin, cl, parsertype, patchinfo, nullptr);

    const data_t *uid=fromjbyteArray(envin,sensorident);
    destruct _destuid([uid]{data_t::deleteex(uid);});
    string serial=getserial(fam,reinterpret_cast<const unsigned char *>(uid->data()));

    time_t starttime=time(NULL);
    const data_t *info=fromjbyteArray(envin,patchinfo);
    destruct _destinfo([info]{data_t::deleteex(info);});

    LOGGER("bluetoothOnKey %s\n",serial.data());
    if(SensorGlucoseData *hist=sensors->getSensorData(serial.data())) {
        LOGSTRING("Old history\n");    
        if(!hist->setbluetooth(starttime,info->data()))
            return nullptr;
        }
    else {
       timevalues times= patchtimevalues(info) ;
        SensorGlucoseData::mkdatabase(pathconcat(sensorbasedir,serial),starttime,(const char *)uid->data(),(const char *)info->data(),&times,3,starttime,(const char *)info->data());
        int ind=sensors->addsensor(serial.data());
        sensor *sen=sensors->getsensor(ind);
        sen->halfdays=times.wear/(30*24)+1;
        }
    scanstate astate;
    const data_t* pay=getStreamingPayload(&astate,uid, info, starttime); 
    if(pay) {
         LOGSTRING("getStreamingPayload returned something\n");
        jint uitlen=pay->size();
        jbyteArray uit=envin->NewByteArray(uitlen);
        envin->SetByteArrayRegion(uit, 0, uitlen, pay->data());
        return uit;
        }
    else
        {
        if(SensorGlucoseData *hist=sensors->getSensorData(serial.data())) {
            hist->setnobluetooth();
//            hist->bluetoothback();
            }
        
         LOGSTRING("getStreamingPayload returned null\n");
        }
    return nullptr;
    }
//    return abbottcall(getEnableStreamingPayload)(envin,nullptr, parsertype, sensorident, patchinfo, person, timeminbase);



string getserial(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo) {
    LOGAR("getserial");
    int fam= libre2_imp::family(envin, cl, parsertype, patchinfo,nullptr);
    const data_t *uid=fromjbyteArray(envin,sensorident);
    destruct _dest([uid] {data_t::deleteex(uid);});
    return getserial(fam,reinterpret_cast<const unsigned char *>(uid->data()));
    }

extern "C" JNIEXPORT jstring  JNICALL fromjava(getserial)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo) {
    LOGAR("jgetserial");
    return envin->NewStringUTF(getserial(envin,cl,sensorident,patchinfo).data()+5);
    }
extern "C" JNIEXPORT void  JNICALL fromjava(bluetoothback)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo) {
    string serial=getserial(envin,cl,sensorident,patchinfo);
    if(SensorGlucoseData *hist=sensors->getSensorData(serial.data())) {
        hist->bluetoothback();
        }
    }

//extern "C" JNIEXPORT jbyteArray  JNICALL fromjava(hasBluetooth)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo,jlong tim) {
extern "C" JNIEXPORT jboolean  JNICALL fromjava(hasBluetooth)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo) {
    if(!patchinfo||envin->GetArrayLength(patchinfo)!=6)return JNI_FALSE;
    auto info=fromjbyteArray(envin,patchinfo);const bool capable=libre2_imp::bluetooth_supported(info);
    data_t::deleteex(info);return capable;
    }

#include "hex.hpp"
bool  setDeviceAddressB(JNIEnv *env,SensorGlucoseData *hist,jbyteArray jaddress ) {
    if(jaddress==nullptr) {
        LOGSTRING("setDeviceAddressB(null)\n");
        return false;
        }
     jsize len=env->GetArrayLength(jaddress);
     if(len!=6) {
        LOGGER("setDeviceAddressB(len=%d)\n",len);
         return false;
        }
    jbyte address[7];
    env->GetByteArrayRegion(jaddress, 0, len, address);

    char *saddress=hist->deviceaddress();
    for(int i=5;i>=0;i--) {
        *saddress++=showhex<1>(address[i]);
        *saddress++=showhex<0>(address[i]);
        *saddress++=':';
        }
    saddress[-1]='\0';
    LOGGER("setDeviceAddressB(%s)\n",hist->deviceaddress());
    return true;
    }

//extern "C" JNIEXPORT void JNICALL   fromjava(enabledStreaming)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo,int val) {
extern void    sendstreaming(SensorGlucoseData *hist) ;
extern "C" JNIEXPORT void JNICALL   fromjava(enabledStreaming)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray patchinfo,jint val,jbyteArray address) {
    LOGGER("enabledStreaming(%d)\n",val);
      setthreadname( "enabledStreaming");
    int fam= libre2_imp::family(envin, cl, parsertype, patchinfo,nullptr);
    const data_t *uid=fromjbyteArray(envin,sensorident);
    destruct _dest([uid] {data_t::deleteex(uid);});
    string serial=getserial(fam,reinterpret_cast<const unsigned char *>(uid->data()));
    if(SensorGlucoseData *hist=sensors->getSensorData(serial.data())) {
        hist->setbluetoothOn( val); 
        if(val==1) 
            setDeviceAddressB(envin,hist,address);
        setbluetoothon=false;
        sendstreaming(hist);
        }
    }
extern "C" JNIEXPORT void JNICALL   fromjava(USenabledStreaming)(JNIEnv *envin, jclass cl,jbyteArray sensorident,jbyteArray jauth,jbyteArray address) {
    const char uslog[]="USenabledStreaming";
    setthreadname(uslog );
    LOGGERN(uslog,sizeof(uslog)-1);
    const int fam=3;
    const data_t *uid=fromjbyteArray(envin,sensorident);
    destruct _dest([uid] {data_t::deleteex(uid);});
    string serial=getserial(fam,reinterpret_cast<const unsigned char *>(uid->data()));
    if(SensorGlucoseData *hist=sensors->getSensorData(serial.data())) {
        hist->setbluetoothOn( 1); 
        setDeviceAddressB(envin,hist,address);
        hist->setsensorgen();
        if(hist->getsensorgen()==2) {
            jsize authlen=envin->GetArrayLength(jauth);
            if(authlen<=12) {
                envin->GetByteArrayRegion(jauth, 0, authlen, (jbyte *)hist->getinfo()->streamingAuthenticationData);
                hist->getinfo()->auth12=authlen==12;
                }
            }
        setbluetoothon=false;
        sendstreaming(hist);
        }
    }

