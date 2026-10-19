#pragma once
#include "sensoren.hpp"
#include "common.hpp"
extern Sensoren *sensors;


inline const SensorGlucoseData *getStreamSensor(int &sensorid) {
	for(;;sensorid--) {
		if(sensorid<0)  {
			return nullptr;
		}
		if(const SensorGlucoseData *sens=sensors->getSensorData(sensorid)) {
			if(sens->pollcount()>0)
				return sens;
		}
	}
}

inline const SensorGlucoseData *getSelectedStreamSensor(int &sensorid) {
	if(const int current=sensors->infoblockptr()->current; current>=0) {
		if(const SensorGlucoseData *sens=sensors->getSensorData(current)) {
			if(sens->pollcount()>0) {
				sensorid=current;
				return sens;
			}
		}
	}
	sensorid=sensors->last();
	return getStreamSensor(sensorid);
}
template <class Funtype>
uint32_t getitems(char *&outiter,const int  datnr,uint32_t newer,uint32_t older,bool alldata, int interval,Funtype writeitem)  {
	LOGGER("getitems %d\n",datnr);
	int sensorid=alldata?sensors->last():sensors->infoblockptr()->current;
	uint32_t timenext=older;
	int datit=0;
	uint32_t lasttime=0;
	while(true) {
		STARTDATA:
		const SensorGlucoseData *sens=alldata?getStreamSensor(sensorid):getSelectedStreamSensor(sensorid);
		if(!sens) {
			return lasttime;
		}
		if(alldata)
			--sensorid;
		time_t starttime= sens->getstarttime();
		if(starttime>=timenext)
			continue;
		std::span<const ScanData> gdata=sens->getPolldata();
		const ScanData *iter=&gdata.end()[-1];
		if(alldata) {
		if(const SensorGlucoseData *sens2=getStreamSensor(sensorid)) {
			std::span<const ScanData> gdata2=sens2->getPolldata();
			const ScanData *last=&gdata2.end()[-1];
			if(last->t>iter->t) {
				sens=sens2;
				iter=last;
				gdata=gdata2;
				starttime= sens->getstarttime();
				}
			}
		}
		auto *sensorname= sens->shortsensorname();
		LOGGER("getStreamSensor(%d) %s pollcount=%d\n",sensorid+1,sensorname->data(),sens->pollcount());
		const ScanData *first=&gdata.begin()[0];



		for(;datit<datnr;datit++,iter--) {
			while(true) {
				if(iter<first) {
					if(alldata) {
					//	--sensorid;
						goto STARTDATA;
						}
					return lasttime;
					}

				if(iter->valid(iter-first)) {
					if(iter->t<newer) {
						return lasttime;
						}
					if(iter->t<timenext)
						break;
				}
				else
					LOGSTRING("invalid\n");
				--iter;

			}
			timenext=iter->t-interval;
                        ScanData exportitem;
                        const ScanData *itemptr=makeExportedScan(sens,iter,sensorname,exportitem);
                        if(itemptr==nullptr)
                                itemptr=iter;

			auto outitem=writeitem(outiter,datit,itemptr,sensorname,starttime);
			if(!datit&&outiter!=outitem)
				lasttime=iter->t;
			outiter=outitem;
		}

	break;
	}
	return lasttime;
}
