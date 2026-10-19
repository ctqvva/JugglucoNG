#pragma once
#include <jni.h>

template <int save,typename T=jbyte>
class CritArBase {
	JNIEnv *env;
	jarray jar;
	T *ar;
public:
   CritArBase(JNIEnv *env,jarray jar):env(env),jar(jar), ar((T*)env->GetPrimitiveArrayCritical(jar, nullptr)) {};
   ~CritArBase()  {
      env->ReleasePrimitiveArrayCritical(jar,ar,save );
      };
   const T *data() const  {	
      return ar;
      }
    T *data()   {
        return ar;
    }
   };


template <typename T=jbyte>
  using CritAr=CritArBase<JNI_ABORT,T>;
template <typename T=jbyte>
   using CritArSave=CritArBase<0,T>;
