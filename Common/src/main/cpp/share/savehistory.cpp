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
/*      Fri Jan 27 12:37:55 CET 2023                                                 */


#include "SensorGlucoseData.hpp"
#include "jnihistory.h"
#include "nfcdata.hpp"
#include "datbackup.hpp"
extern bool saveSputnik_PG2(const jniHistory &hist,time_t nutime,int nuid,const nfcdata  *nfcptr, SensorGlucoseData &save) ;
/* An NFC scan carries the last 32 history records (8 hours at 15 minutes). Every
   one of them is walked, not just the records past the previous scan end: a BLE
   streaming outage leaves holes below that end, and only a later scan can still
   repair them. Ranking, oldest rule first:
     - a slot holding a valid BLE-streamed value is never touched (1-minute
       accurate, better than the 15-minute scan copy);
     - a slot below the previous scan end is filled only while it is still empty;
     - a slot at or past the previous scan end is refreshed as before.
   Records the algorithm did not vouch for are dropped instead of stored as 0 —
   the newest record of a scan is regularly still in progress and comes back as
   0, and writing that would erase whatever the slot already held. */
bool saveSputnik_PG2(const jniHistory &hist,time_t nutime,int nuid,const nfcdata  *nfcptr, SensorGlucoseData &save) {
     jint len=hist.size();
    LOGGER("saveSputnik_PG2 size=%d\n",len);
    if(len<=0)
    	return false;
const int mininterval=save.getmininterval();
if(mininterval<=0) {
	LOGGER("saveSputnik_PG2 mininterval=%d\n",mininterval);
	return false;
	}
decltype(auto)  first=hist.get(0);
    int fid=first.getId();
    int pos=int(round(fid/(double)mininterval));
/* Side effect: pulls starthistory down to the oldest scanned position. */
    const int prevend=save.getlastpos(pos);
    if(pos>=prevend&&save.getstarthistory()<=0) {
	save.setstarthistory(pos);
	}
const int uselen=std::min(history::num,len);
const int histmaxpos=save.maxpos();
int lastgood=std::max(prevend,pos)-1;
int firstchanged=-1;
LOGGER("saveSputnik_PG2 uselen=%d prevend=%d firstpos=%d\n",uselen,prevend,pos);
    for(int i=0;i<uselen;i++) {
	GlucoseValue gluv=hist.get(i);
	const int id=gluv.getId();
	const int topos=int(round(id/(double)mininterval));
	if(topos<0||topos>=histmaxpos) {
		LOGGER("GLU: savehistory pos %d out of 0..%d\n",topos,histmaxpos);
		continue;
		}
	if(!gluv.getQuality())
		lastgood=std::max(lastgood,topos);
	const int value=gluv.getValue();
	if(gluv.getQuality()||value<=38||value>=502) {
		LOGGER("GLU: savehistory skip %d quality=%d value=%d\n",topos,gluv.getQuality(),value);
		continue;
		}
	Glucose *item=save.getglucose(topos);
	if(item->valid()) {
		if(item->isStreamed()) {
#ifndef NOLOG
			time_t tim=item->gettime();
			LOGGER("already streamed %d %.1f %s",item->getid(),(float)item->getmgdL()/convfactordL,ctime(&tim));
#endif
			continue;
			}
		if(topos<prevend)  /*Already written by an earlier scan.*/
			continue;
		}
	const uint16_t gv=10*value;//Same unit as raw
	const uint32_t was=nutime-(nuid-id)*60;
	const uint16_t rawel= nfcptr?nfcptr->gethistoryglucose(i):0;
	*item={.time=was,.id=(uint16_t)id};
	item->glu[0]=rawel;item->glu[1]=gv;
	item->glu[2]&=~0x4000;	/*Scan-sourced: keep the streamed flag truthful.*/
	if(firstchanged<0||topos<firstchanged)
		firstchanged=topos;
#ifndef NOLOG
	time_t tim=item->gettime();
	LOGGER("add %d %.1f %s",item->getid(),(float) item->getmgdL()/convfactordL,ctime(&tim));
#endif
    	}
save.setendScanhistory(lastgood+1);
if(save.getStreamendhistory()) {
	if(firstchanged!=-1) {
		int maxint=backup->getupdatedata()->sendnr;
		save.setstarthistback(maxint,firstchanged);
		const auto first=std::max((uint16_t)firstchanged,save.getinfo()->startedwithStreamhistory);
		if(save.getinfo()->libreviewnotsendHistory>first) {
			LOGGER("libreviewnotsendHistory=%d\n",first);
			save.getinfo()->libreviewnotsendHistory=first; 
			}
		}
	}

return true;
}

extern int saveScanTrend(const nfcdata *nfcptr,time_t nutime,int nuid, SensorGlucoseData &save) ;
/* Besides the 32 fifteen-minute history records, the patch carries trend[16]: the raw
   sensor value for each of the 16 minutes before the scan. Juggluco kept those only in
   trends.dat for the legacy trend thumbnail, so scanning during a BLE outage recovered
   nothing at one-minute resolution even though the tag was holding it. Append the minutes
   we do not have yet.

   These are uncompensated sensor values in the same unit as the history raw lane
   (mg/dL x 10), not algorithm output, so they go into the raw lane with no auto value.
   Only the Abbott library can turn a raw sample into a glucose reading, and it exposes
   that for the current minute alone — deriving the other fifteen would be inventing
   readings, which this app does not do.

   The newest entry is left out on purpose: the scan itself already records that minute
   with a real algorithm value, and a raw-only poll at the same second would displace it
   in getGlucoseHistory(). */
int saveScanTrend(const nfcdata *nfcptr,time_t nutime,int nuid, SensorGlucoseData &save) {
	if(!nfcptr)
		return 0;
	int added=0,skipped=0;
	for(int i=0;i<trend::num-1;i++) {
		const uint16_t raw=nfcptr->gettrendglucose(i);
		if(!raw)	/*Ring buffer not filled yet.*/
			continue;
		/*Same plausibility window Glucose::valid() applies to this unit.*/
		if(raw<=380||raw>=5020) {
			LOGGER("GLU: scantrend implausible raw=%hu\n",raw);
			continue;
			}
		const int minutesback=trend::num-1-i;
		const int id=nuid-minutesback;
		if(id<0)
			continue;
		if(!save.validPollIndex(save.pollcount())) {
			LOGGER("GLU: scantrend poll store full at %d\n",save.pollcount());
			break;
			}
		const time_t was=nutime-minutesback*60;
		/*rawpolls holds mmol/L x 10, see compactRawMgdl() in g.cpp.*/
		const int rawpoll=(int)lround(raw/convfactordL);
		/*savepoll() refuses anything at or before the newest stored id, so minutes the
		  BLE stream already delivered keep their algorithm value untouched.*/
		if(save.savepoll(was,id,0,0,NAN,rawpoll)) {
			++added;
			LOGGER("scantrend add %d %.1f raw %s",id,raw/convfactor,ctime(&was));
			}
		else
			++skipped;
		}
	LOGGER("saveScanTrend added=%d already known=%d\n",added,skipped);
	return added;
	}

extern std::vector<int> usedsensors;
extern void setusedsensors();
void setstartedwithStreamhistory() {
	if(SensorGlucoseData *sens=sensors->getSensorData()) {
		if(sens->isLibre2()&&!sens->getinfo()->startedwithStreamhistory) {
			sens->getinfo()->startedwithStreamhistory=std::max(sens->getinfo()->endhistory,1);
			}
		}
	if(settings->data()->streamHistLib)  {
		return;
		}
	setusedsensors();
	for(int index:usedsensors) {
		  SensorGlucoseData *sens=sensors->getSensorData(index );
		  if(sens&&sens->isLibre2()) {
			sens->getinfo()->startedwithStreamhistory=std::max(sens->getinfo()->endhistory,1);
			}
		}
	settings->data()->streamHistLib=true;
	}
void setendedwithStreamhistory() {
	if(!settings->data()->streamHistLib) 
		return;
	setusedsensors();
	for(int index:usedsensors) {
		  SensorGlucoseData *sens=sensors->getSensorData(index );
		  if(sens&&sens->isLibre2()) {
			sens->getinfo()->startedwithStreamhistory=0;
			}
		}
	settings->data()->streamHistLib=false;
	}
extern bool addStreamHistory(const jniHistory &hist,time_t nutime,int nuid, SensorGlucoseData &save) ;
bool addStreamHistory(const jniHistory &hist,time_t nutime,int nuid, SensorGlucoseData &save) { 
	jint len=hist.size();
	LOGGER("addStreamHistory size=%d\n",len);
	if(len<2)  {
		return false;
		}
	int endhist=save.getAllendhistory();
	GlucoseValue   histvalue=hist.get(len-2);
	if(!histvalue.valid()) {
		LOGAR("not valid");
		return false;
		}
	int id=histvalue.getId();
	int pos=int(round(id/ save.getmininterval()));
	if(pos<endhist)  {
		LOGAR("old value");
		return false;
		}
	uint16_t gv=10*histvalue.getValue();//Same unit as raw
	time_t was=nutime-(nuid-id)*60;
	LOGGER("glucose id=%d %.1f (%d) %ld %s",id,gv/180.0f,gv,was,ctime(&was));
	save.saveel(pos,was,id, {0,gv,0x4000});
	save.setendStreamhistory(pos+1);
	if(!save.getstarthistory()) {
		save.setstarthistory(pos);
		}
	return true;
	}
