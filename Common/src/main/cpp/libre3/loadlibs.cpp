#ifdef LIBRE3
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
/*      Fri Jan 27 15:22:01 CET 2023                                                 */

#include <jni.h>
#include <string.h>
#include <array>
#include <cinttypes>
#include "dp_activation.hpp"
#include "logs.hpp"
#include "settings/settings.hpp"


static jclass      myFindClass(JNIEnv*, const char* name) {
	LOGGER("FindClass %s\n",name);
	return reinterpret_cast<jclass>(const_cast<char *>(name));
	}
//#define cryptfunc(x) Java_tk_glucodata_ECDHCrypto_ ##x
#define cryptfunc(x) x

extern "C"  {
typedef  jint  JNICALL   (*process1Type)(JNIEnv *env, jclass _cl,jint i2, jbyteArray bArr, jbyteArray bArr2);
static process1Type process1=nullptr;
typedef   jbyteArray  JNICALL   (*process2Type)(JNIEnv *env, jclass _cl,jint i2, jbyteArray bArr, jbyteArray bArr2);
static process2Type process2=nullptr;
typedef jint JNICALL (*DPGetActivationCommandDataType)(JNIEnv *env, jclass _cl, jbyteArray bArr, jlong, jlong);
static DPGetActivationCommandDataType DPGetActivationCommandData=nullptr;
};

//static  jbyteArray  JNICALL   (*process2)(JNIEnv *env, jclass _cl,jint i2, jbyteArray bArr, jbyteArray bArr2);
static jint        myRegisterNatives(JNIEnv*, jclass name, const JNINativeMethod*methods, jint nr) {
	LOGSTRING("myRegisterNatives\n");
    const char *namestr=reinterpret_cast<const char *>(name);
	if(namestr&&methods&&nr>=2&&!strcmp(namestr,"com/adc/trident/app/frameworks/mobileservices/libre3/security/Libre3SKBCryptoLib")) {
        	process1=(process1Type)methods[0].fnPtr;
		process2=(process2Type)methods[1].fnPtr;
		}
	else {
		LOGGER("class=%s\n",namestr?namestr:"null");
		if(namestr&&methods&&nr>5&&!strcmp(namestr,"com/adc/trident/app/frameworks/mobileservices/libre3/libre3DPCRLInterface")) {
			LOGGER("register %s%s\n",	methods[5].name,methods[5].signature);
			DPGetActivationCommandData=( DPGetActivationCommandDataType)methods[5].fnPtr;
			}
		}
	LOGSTRING("end myRegisterNatives\n");
	return 0;
	}
	
static const struct JNINativeInterface envfunctions {
.FindClass=myFindClass,
.RegisterNatives =myRegisterNatives
};
 JNIEnv theenv={.functions=&envfunctions};

jint        mygetenv(JavaVM*, void**envptr, jint) {
	JNIEnv **env=reinterpret_cast<JNIEnv**>(envptr);
	*env=&theenv;
	return 0;
	}

static  JNIInvokeInterface myvm{.GetEnv=mygetenv};


#include <dlfcn.h>
#include "fromjava.h"
#include <string_view>
extern std::string_view libdirname;

#define CHANGECODE
#ifdef CHANGECODE
#include "unprotect.hpp"
#endif
#include "hexstr.hpp"
/*
void changelib(uint8_t *from) {
        uint8_t *start= (uint8_t*)from+2432 +7560 -4;
        uint8_t was[]={0xE1,0x00,0x00,0x94};
        hexstr isnow(start,4);
   if(!memcmp(start,was,4)) 
        {
        LOGGER("Sign de same =%s\n",isnow.str());
#ifdef CHANGECODE
        Unprotect unpr(start,4);
        uint8_t nop[]={0xE0,0x03,0x00,0xAA};
        memcpy(start,nop,4);
#endif
        }
else {   
        LOGGER("Sign different =%s\n",isnow.str());
        }
        }
*/
#if defined(__aarch64__) 
void changelib(uint8_t *from) {
   	settings->data()->triedasm=true;
	const uint8_t was[]{0x04,0x2E,0x05,0x94};
	uint8_t *start=(uint8_t*)from+0x119c+7560 -4;
#ifndef NOLOG
        hexstr isnow(start,4);
#endif
   if(!memcmp(start,was,4)) 
        {
        LOGGER("Sign de same =%s\n",isnow.str());
#ifdef CHANGECODE
        Unprotect unpr(start,4);
        uint8_t nop[]={0xE0,0x03,0x00,0xAA};
        memcpy(start,nop,4);
#endif
        }
else {
        LOGGER("Sign different =%s\n",isnow.str());
        }
	}
#endif
#define EXTRA 100

extern bool globalsetpathworks;
extern bool rootcheck;
static bool doOnLoad(std::string_view libname,bool change) {
#if defined(__aarch64__) 
   if(settings->data()->triedasm&&!settings->data()->asmworks&& globalsetpathworks) 
#else
	if(globalsetpathworks)  
#endif	
	{
		extern pathconcat mkbindir(std::string_view subdir,std::string_view libname );
		const static pathconcat libre3dir=mkbindir("bin","libinit.so");
		setenv("PATH", libre3dir.data(), 1);
		}
	rootcheck=true;

	int libnamelen=libname.size()+1;
	int liblen=libdirname.length();
	char fullpath[libnamelen+ liblen+EXTRA];
	memcpy(fullpath,libdirname.data(),liblen);
	memcpy(fullpath+liblen,libname.data(),libnamelen);
	LOGGER("open %s\n",fullpath);
	void *handle=dlopen(fullpath, RTLD_NOW);
	if(!handle) {
		LOGGER("dlopen %s failed: %s\n",fullpath,dlerror());
		return false;
		}
	const char name[]{"JNI_OnLoad"};
       typedef   jint (*OnLoadtype)(JavaVM* vm, void* reserved) ;
	LOGGER("opened .%s.\n",fullpath);
         OnLoadtype OnLoad= (OnLoadtype)dlsym(handle, name);
	 if(!OnLoad) {
	 	LOGGER("dlsym %s failed\n",name);
		return false;
	 	}
	LOGSTRING("found OnLoad\n");

#if defined(__aarch64__) 
	if(change) {
	     changelib((uint8_t*)OnLoad);
		}
#endif
	JavaVM vmptr{.functions=&myvm};
	const jint version=OnLoad(&vmptr,nullptr);
	if(version==JNI_ERR) {
		LOGSTRING("JNI_OnLoad returned JNI_ERR\n");
		return false;
		}
	LOGSTRING("after OnLoad\n");
if(change)
	LOGGER("process1-OnLoad %ld\n",(uint8_t*)process1-(uint8_t*)OnLoad);
	return true;
	}
////////////////extern "C" JNIEXPORT jboolean JNICALL fromjava(loadECDHCrypto)(JNIEnv *env, jclass thiz) {

static bool loadECDHCrypto(const bool changelib) {
	
	
	if(process1) {
		return true;
		}
	auto res= doOnLoad("/liblibre3extension.so",changelib);
/*
        uint8_t *start= (uint8_t*)process1+2432-4;
        uint8_t was[]={0xE1,0x00,0x00,0x94};
        hexstr isnow(start,4);
   if(!memcmp(start,was,4)) 
        {
        LOGGER("Sign de same =%s\n",isnow.str());
#ifdef CHANGECODE
        Unprotect unpr(start,4);
        uint8_t nop[]={0xE0,0x03,0x00,0xAA};
        memcpy(start,nop,4);
#endif
        }
	else {     
		LOGGER("Sign different =%s\n",isnow.str());
		} */
	return res&&process1&&process2;
/*
	const JNINativeMethod  funcs[]{{"process1","(I[B[B)I",(void*)process1}, {"process2","(I[B[B)[B",(void*)process2}};
	const char classname[]="tk/glucodata/ECDHCrypto";
	jclass cl=env->FindClass(classname);
	if(!cl) {
		LOGGER("Can't find %s\n",classname);
		return false;
		}
	int rc=env->RegisterNatives(cl, funcs, 2);
   	env->DeleteLocalRef(cl);
	    if (rc != JNI_OK)  {
		    LOGSTRING("RegisterNatives failed\n");
		    return false;
		    }
	    LOGSTRING("RegisterNatives  OK\n"); 
	return true;	
	*/
	}
//extern "C" JNIEXPORT jboolean JNICALL fromjava(loadNFC)(JNIEnv *env, jclass thiz) {
static bool loadNFC() {
	if(DPGetActivationCommandData)
		return true;
	LOGSTRING("loadNFC\n");
	return doOnLoad("/libcrl_dp.so",false)&&DPGetActivationCommandData;
	/*
	LOGSTRING(	"after OnLoad\n");
	const char classname[]="tk/glucodata/libre3/NFC";
	jclass cl=env->FindClass(classname);
	if(!cl) {
		LOGGER("Can't find %s\n",classname);
		return false;
		}
	LOGGER("found %s\n",classname);
	const JNINativeMethod  funcs[]{{ "DPGetActivationCommandData", "([BJJ)I",(void*) DPGetActivationCommandData}};
	int rc=env->RegisterNatives(cl, funcs, std::size(funcs));
	LOGSTRING("after RegisterNatives\n");
   	env->DeleteLocalRef(cl);
	  if (rc != JNI_OK)  {
		    LOGGER("RegisterNatives %s failed\n",classname);
		    return false;
		    }
	LOGGER("RegisterNatives %s OK\n",classname);
	return true;	 */
		}

static bool legacyActivationCommand(JNIEnv *env, jclass cl, jlong time,
                                     jlong account,
                                     std::array<jbyte, 10> &output) {
	if(!DPGetActivationCommandData)
		return false;
	jbyteArray array=env->NewByteArray(static_cast<jsize>(output.size()));
	if(!array)
		return false;
#if defined(__arm__)
	const jint result=DPGetActivationCommandData(env,cl,array,account,time);
#else
	const jint result=DPGetActivationCommandData(env,cl,array,time,account);
#endif
	if(result==JNI_OK&&!env->ExceptionCheck())
		env->GetByteArrayRegion(array,0,static_cast<jsize>(output.size()),output.data());
	const bool success=result==JNI_OK&&!env->ExceptionCheck();
	env->DeleteLocalRef(array);
	return success;
	}

enum class ActivationVerification {
	unavailable,
	match,
	mismatch,
	error,
	};
constexpr jint establishedActivationFallback=1;

static ActivationVerification verifyActivationGenerator(
    JNIEnv *env, jclass cl, jlong time, jlong account,
    std::array<jbyte,10> &legacyActual) {
	if(!loadNFC())
		return ActivationVerification::unavailable;
	constexpr std::array<std::array<jlong,2>,7> cases{{
		{{1,0}},
		{{9,12}},
		{{0x6286428dLL,0x1f416d8dLL}},
		{{0x7fffffffLL,0}},
		{{0x80000000LL,0xffffffffLL}},
		{{0xffffffffLL,0x12345678LL}},
		{{0x100000001LL,0x100000002LL}},
	}};
	bool mismatch=false;
	for(const auto &test:cases) {
		std::array<jbyte,10> legacy{};
		if(!legacyActivationCommand(env,cl,test[0],test[1],legacy))
			return env->ExceptionCheck()
				? ActivationVerification::error
				: ActivationVerification::unavailable;
		const auto source=dp::activation_command_data(test[0],test[1]);
		if(memcmp(legacy.data(),source.data(),source.size())) {
			LOGGER("Libre 3 activation generator mismatch for %" PRId64 ",%" PRId64 "\n",
			       test[0],test[1]);
			mismatch=true;
			}
		}
	if(!legacyActivationCommand(env,cl,time,account,legacyActual))
		return env->ExceptionCheck()
			? ActivationVerification::error
			: ActivationVerification::unavailable;
	const auto source=dp::activation_command_data(time,account);
	if(memcmp(legacyActual.data(),source.data(),source.size())) {
		LOGGER("Libre 3 activation generator mismatch for actual payload %" PRId64 ",%" PRId64 "\n",
		       time,account);
		mismatch=true;
		}
	return mismatch ? ActivationVerification::mismatch
	                : ActivationVerification::match;
	}

#include "debugclone.hpp"
extern "C" JNIEXPORT jint JNICALL fromjava(startTimeIDsum)(JNIEnv *env, jclass cl, jbyteArray bArr, jlong time, jlong account) {

	settings->setnodebug(false);
	LOGSTRING("startTimeIDsum\n");
	usedebug use(false,3);
	if(!env||!bArr||env->GetArrayLength(bArr)!=10) {
		LOGSTRING("startTimeIDsum requires a 10-byte output array\n");
		return JNI_ERR;
		}
	std::array<jbyte,10> legacy{};
	const auto verification=verifyActivationGenerator(env,cl,time,account,legacy);
	if(verification==ActivationVerification::error)
		return JNI_ERR;
	if(verification==ActivationVerification::mismatch) {
		// The established payload was generated before this copy, so a
		// mismatch never reaches the sensor with source-generated bytes.
		env->SetByteArrayRegion(bArr,0,static_cast<jsize>(legacy.size()),legacy.data());
		if(env->ExceptionCheck())
			return JNI_ERR;
		LOGSTRING("source activation verification failed; using established payload\n");
		return establishedActivationFallback;
		}
	// The source implementation is self-contained and compile-time checked.
	// An unavailable closed library only removes the optional comparison; it
	// must not make that closed library a runtime dependency again.
	const auto source=dp::activation_command_data(time,account);
	env->SetByteArrayRegion(bArr,0,static_cast<jsize>(source.size()),
	                        reinterpret_cast<const jbyte *>(source.data()));
	if(env->ExceptionCheck())
		return JNI_ERR;
	if(verification==ActivationVerification::match)
		LOGGER("source activation command verified for %" PRId64 ",%" PRId64 "\n",
		       time,account);
	else
		LOGGER("source activation command used without runtime comparison for %" PRId64 ",%" PRId64 "\n",
		       time,account);
	return JNI_OK;
	}

extern thread_local pid_t has_debugger;
extern bool libre3initialized;
bool libre3initialized=false;
extern bool wrongfiles() ;
extern "C" JNIEXPORT jint JNICALL fromjava(processint)(JNIEnv *env, jclass cl,jint i2, jbyteArray bArr, jbyteArray bArr2) {
#if defined(__aarch64__) 
	const bool changelib=settings->data()->asmworks||!settings->data()->triedasm;
LOGGER("asmworks=%d settings->data()->triedasm=%d\n", settings->data()->asmworks,settings->data()->triedasm);

#else
	const bool changelib=false;
	LOGSTRING("not __arch64__\n");
#endif
	LOGGER("setpathworks=%d libre3initialized=%d\n",globalsetpathworks,libre3initialized);
static	const bool debug=!changelib&&!globalsetpathworks;

	settings->setnodebug(false);
	usedebug use(debug&&!libre3initialized,3);
	const bool loaded=loadECDHCrypto(changelib);
	LOGGER("%d processint(%d,%p,%p) process1==%p\n",loaded,i2,bArr,bArr2,process1);
	if(!loaded||!process1) {
		LOGSTRING("processint: Libre 3 security library unavailable\n");
		return JNI_ERR;
		}
	jint res=process1(env,cl,i2,bArr,bArr2);
	LOGGER("processint=%d\n",res);
	if(use.pid>=sizeof(long)&&!wrongfiles())  {
		getsid(use.pid);
		has_debugger=0;
		}
	if(changelib)
		settings->data()->asmworks=true;
	return res;
	}
/*
extern "C" JNIEXPORT void JNICALL fromjava(enddebug)(JNIEnv *env, jclass cl) {
	LOGSTRING("enddebug\n");
    if(has_debugger) {
		getsid(has_debugger);
		has_debugger=0;
		}
	} */
extern "C" JNIEXPORT jbyteArray JNICALL fromjava(processbar)(JNIEnv *env, jclass cl,jint i2, jbyteArray bArr, jbyteArray bArr2) {
#if defined(__aarch64__) 
	const bool changelib=settings->data()->asmworks||!settings->data()->triedasm;
#else
	const bool changelib=false;
#endif
static	const bool debug=!changelib&&!globalsetpathworks;
	LOGAR("processbar start");
	settings->setnodebug(false);
	usedebug use(debug&&!libre3initialized,3);
	if(!loadECDHCrypto(changelib)||!process2) {
		LOGSTRING("processbar: Libre 3 security library unavailable\n");
		return nullptr;
		}
	auto res=process2(env,cl,i2,bArr,bArr2);
	if(use.pid>=sizeof(long)&&!wrongfiles()) {
		getsid(use.pid);
		has_debugger=0;
		}
	if(changelib)
		settings->data()->asmworks=true;
	LOGAR("processbar end");
	return res;
	}
//static  jint  JNICALL   *cryptfunc(process1)(JNIEnv *env, jclass _cl,jint i2, jbyteArray bArr, jbyteArray bArr2);
//static  jbyteArray  JNICALL   *cryptfunc(process2)(JNIEnv *env, jclass _cl,jint i2, jbyteArray bArr, jbyteArray bArr2);


//debugclone();
/*
void testonce() {
mybyteArray anar{ (jbyte)0x01,(jbyte)0x51,(jbyte)0x41,(jbyte)0x08,(jbyte)0x93,(jbyte)0x4C,(jbyte)0x00,(jbyte)0x7A,(jbyte)0xE0,(jbyte)0xD1,(jbyte)0x4A,(jbyte)0x04,(jbyte)0x88,(jbyte)0x1A,(jbyte)0xCC,(jbyte)0x74,(jbyte)0xEE,(jbyte)0xC1,(jbyte)0xD7,(jbyte)0x79,(jbyte)0x1F,(jbyte)0xB8,(jbyte)0x88,(jbyte)0x05,(jbyte)0x13,(jbyte)0x74,(jbyte)0x10,(jbyte)0xD1,(jbyte)0x05,(jbyte)0x75,(jbyte)0x25,(jbyte)0xAF,(jbyte)0x22,(jbyte)0x93,(jbyte)0x24,(jbyte)0xFC,(jbyte)0x0B,(jbyte)0x8C,(jbyte)0x63,(jbyte)0x47,(jbyte)0x31,(jbyte)0x7B,(jbyte)0x4A,(jbyte)0x03,(jbyte)0x2E,(jbyte)0x0F,(jbyte)0xEA,(jbyte)0xBA,(jbyte)0x87,(jbyte)0xDE,(jbyte)0xA3,(jbyte)0x04,(jbyte)0x52,(jbyte)0x71,(jbyte)0x8E,(jbyte)0x47,(jbyte)0x5E,(jbyte)0x71,(jbyte)0x20,(jbyte)0x8B,(jbyte)0x84,(jbyte)0x6B,(jbyte)0xEA,(jbyte)0xAC,(jbyte)0x15,(jbyte)0xE0,(jbyte)0xB2,(jbyte)0x4C,(jbyte)0x79,(jbyte)0x43,(jbyte)0xB7,(jbyte)0xAA,(jbyte)0xDE,(jbyte)0xB4,(jbyte)0x5C,(jbyte)0x3C,(jbyte)0xA7,(jbyte)0x6D,(jbyte)0xBC,(jbyte)0x26,(jbyte)0xAE,(jbyte)0xC2,(jbyte)0x76,(jbyte)0x43,(jbyte)0xE1,(jbyte)0xF0,(jbyte)0xF9,(jbyte)0xE5,(jbyte)0x1A,(jbyte)0xC3,(jbyte)0x39,(jbyte)0xF0,(jbyte)0x71,(jbyte)0x2D,(jbyte)0x3D,(jbyte)0x11,(jbyte)0x7B,(jbyte)0xB4,(jbyte)0xF2,(jbyte)0x71,(jbyte)0x91,(jbyte)0xFB,(jbyte)0xD7,(jbyte)0x70,(jbyte)0x2F,(jbyte)0x4C,(jbyte)0xD6,(jbyte)0x81,(jbyte)0xB7,(jbyte)0x03,(jbyte)0x58,(jbyte)0x21,(jbyte)0x87,(jbyte)0xFB,(jbyte)0x81,(jbyte)0xA2,(jbyte)0x85,(jbyte)0x36,(jbyte)0x5B,(jbyte)0xE2,(jbyte)0xEC,(jbyte)0x18,(jbyte)0xFD,(jbyte)0x4C,(jbyte)0x2E,(jbyte)0xB5,(jbyte)0x46,(jbyte)0xE6,(jbyte)0x5F,(jbyte)0xEB,(jbyte)0x08,(jbyte)0xB9,(jbyte)0x1A,(jbyte)0xAE,(jbyte)0xFB,(jbyte)0x08,(jbyte)0x06,(jbyte)0x98,(jbyte)0x9B,(jbyte)0xFF};
getchar();
  jint res=fromjava(processint)(& theenv,nullptr,4,(jbyteArray)&anar,nullptr);
  }
*/
#endif
