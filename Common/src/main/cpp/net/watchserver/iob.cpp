#include <math.h>
#include <mutex>
#include <stdint.h>
#include "nums/numdata.hpp"
#include "nightnumcategories.hpp"
#include "settings/settings.hpp"
extern vector<Numdata*> numdatas;

/*
From "derived" from formula's in https://ph.pollub.pl/index.php/jcsi/article/view/1294
*/
//0.0000273563 (36554.6 - 5.8 t^2 + 0.0613333 t^3 - 0.0003045 t^4 + 8.146*10^-7 t^5 - 1.126*10^-9 t^6 + 6.29714*10^-13 t^7)
/*
long double iobfiasp(long double t){
   return 1. - 0.000158667 *powl(t,2) + 1.67786*powl(10,-6) *powl(t,3) - 8.33001*powl(10,-9) *powl(t,4) + 2.22845*powl(10,-11) *powl(t,5) - 3.08032*powl(10,-14) *powl(t,6) + 1.72267*powl(10,-17) *powl(t,7);
   }
long double iobfracold(long double t){return 0.0000273563*(36554.6 - 5.8 * powl( t,2) + 0.0613333 * powl(t,3) - 0.0003045* powl(t,4) + 8.146*powl(10,-7)*powl( t,5) - 1.126*powl(10,-9) * powl(t,6) + 6.29714*powl(10,-13)*powl( t,7));
    } */
//s/\([a-z0-9-.]*\)^\([0-9a-z-.]*\)/*powl(\1,\2)/g
//s/\*\*/*/g
/*
static long double iobNovoRapid(long double t) {
   return 1. - 0.00013146 *powl(t,2) + 1.32911*powl(10,-6) *powl(t,3) - 6.55082*powl(10,-9) *powl(t,4) + 1.77615*powl(10,-11) *powl(t,5) - 2.50625*powl(10,-14) *powl(t,6) + 1.43098*powl(10,-17) *powl(t,7);

    } */

static long double iobHuman(long double u,long double t) {
    return (-1.29116L* expl(-0.0117121L* t) + 2.29116 *expl(-0.00660024L* t))* u;
    }

static long double iobNovoRapid(long double level,long double t) {
    return level*(-4218.2L*expl(-0.0148748L*t) + 4219.2L*expl(-0.0148713L *t));
    }
//Different doses seemed to have different maximal value times for blood insulin concentration in the study results I saw.
//Affezza didn't have that and for other insulins I didn't look at different doses. Lazy. Should also somehow be merged in one formula.
static long double iobGlulisine(long double u, long double t) {
   if(u>=23.8934) 
        return 	(-9.24692L * expl(-0.0144733L* t) +10.2469L *expl(-0.0130609L *t))* u;
   if(u<5.97336)
        return (-0.701781L * expl(-0.0316719L* t) +1.70178L * expl(-0.0130609L* t))* u;
   if(11.9467<=u&&u<23.8934)
        return (expl(-0.0496684L* t)* u* (expl(0.035195L *t)* (858.026L -71.821L* u)+expl(0.0275342L* t)* (-232.749L+9.74111L *u)+expl(0.0366075L* t)* (-556.38L+63.0799L* u)))/(68.897L + u);
   return (expl(-0.066867L* t)* u* (expl(0.0447328L* t)* (754.518L -126.314L* u)+expl(0.035195L* t)* (-727.298L+60.8785L* u)+expl(0.0538061L* t)* (484.988L +66.4353L* u)))/(512.207L + u);
    }

static long double iobLispro(long double u,long double t) {
    return (-4486.77L* expl(-0.014844L* t) + 4487.77L* expl(-0.0148407L* t))* u;
    }

static long double iobFiasp(long double u,long double t) {
    return (-4.79525L* expl(-0.0193717L *t) + 5.79525L * expl(-0.016029L* t))* u;
    }
static long double iobURli(long double u,long double t) {
    return (-0.430186L *expl(-0.034645L* t)+1.43019 *expl(-0.0104209L* t))* u;
    }

static long double iobAfrezza(long double u,long double t) {
    return (-0.460126L *expl(-0.0933705L* t) + 1.46013L *expl(-0.0294236L* t))* u;
    }

static long double (*iobfuncs[])(long double level,long double t)= {
    nullptr,
    iobHuman,
    iobNovoRapid,
    iobLispro,
    iobGlulisine,
    iobFiasp,
    iobURli,
    iobAfrezza
    };


static_assert(insulinsNR()==std::size(iobfuncs));

static long double iobformula(long double level, long double agemin,Insulin in) {
        return iobfuncs[static_cast<uint8_t>(in)](level,agemin);
        }



    
/*
The Java journal pushes its insulin/carb totals here on every glucose reading
(Natives.setJournalIob). While fresh, they take precedence over the
numdatas computation below, so webserver pollers (e.g. GlucoDataHandler's
Juggluco IOB support reading /pebble) see the same numbers as the
glucodata.Minute broadcast instead of zeros from an unused native store.
The next30 arguments are the amounts delivered/absorbed in the 30 minutes
after the push; they let serve time interpolate the decay between pushes.
A push older than that window is stale (sensor gone, app layer dead) and
/pebble falls back to the native computation. NAN means no journal data of
that kind: iob falls back, cob is simply not served.
*/
static std::mutex journalmutex;
static float journaliob=NAN,journaliobnext30=NAN,journalcob=NAN,journalcobnext30=NAN;
static int64_t journaltime=0;
constexpr int64_t journalwindowmsec=30LL*60LL*1000LL;

void setjournaliob(float iob,float iobnext30,float cob,float cobnext30,int64_t timmsec) {
   std::lock_guard<std::mutex> lock(journalmutex);
   journaliob=iob;
   journaliobnext30=iobnext30;
   journalcob=cob;
   journalcobnext30=cobnext30;
   journaltime=timmsec;
   }

static double journalvalue(float value,float next30,int64_t agemsec) {
   if(isnan(value))
      return NAN;
   if(isnan(next30)||agemsec<=0)
      return value;
   const double gone=next30*((double)agemsec/journalwindowmsec);
   return fmax(0.0,value-gone);
   }

static bool journalfresh(int64_t agemsec) {
   return journaltime!=0&&agemsec>=0&&agemsec<=journalwindowmsec;
   }

double getiob(uint32_t now);

double pebbleiob(uint32_t now) {
   {
   std::lock_guard<std::mutex> lock(journalmutex);
   const int64_t agemsec=now*1000LL-journaltime;
   if(journalfresh(agemsec)) {
      const double iob=journalvalue(journaliob,journaliobnext30,agemsec);
      if(!isnan(iob))
         return iob;
      }
   }
   return getiob(now);
   }

bool pebblecob(uint32_t now,double *cob) {
   std::lock_guard<std::mutex> lock(journalmutex);
   const int64_t agemsec=now*1000LL-journaltime;
   if(!journalfresh(agemsec))
      return false;
   const double value=journalvalue(journalcob,journalcobnext30,agemsec);
   if(isnan(value))
      return false;
   *cob=value;
   return true;
   }

double getiob(uint32_t now) {
//   uint32_t oldage=now-6*60*60;
   uint32_t oldage=now-16*60*60;
   long double iob=0.0L;
#ifndef NOLOG
   time_t tim=now;
   LOGGER("getiob(%d) %s",now,ctime(&tim));
#endif
   for(const auto *nd:numdatas) {
      const Num *start=nd->begin();
      const Num *last=nd->end()-1;
      for(const Num *it=last;it>=start;--it) {
         if(nd->valid(it)) {
            if(it->time<=oldage)
               break;
            const Insulin instype=settings->getIOBtype(it->type);
            LOGGER("type=%d insulintype=%d val=%.1f\n",it->type,(uint8_t)instype,it->value);
            if(instype!=Insulin::Not) {
                   const int  agesec=now-it->time;
                   if(agesec>=0)  {
                      long double agemin= agesec/60;
                      iob+=iobformula(it->value,agemin,instype);
                   }
               }
            }
         }
      }
   return iob;
   }
