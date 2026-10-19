#!/usr/bin/env python3
"""Exercise the production NFC record method with synthetic storage and records.

No Android, BLE, app data, or JNI is used. The method is extracted verbatim so
this also checks that the NFC-only reconciliation is wired into both paths.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'Common/src/main/cpp/sensoren.hpp').read_text()

def method(marker):
    begin = source.index(marker)
    end = source.index('{', begin) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[begin:end]

code = r'''
#include <array>
#include <string>
#include <string_view>
#include <cstring>
#include <cstdint>
#include <cassert>
#include <iostream>
#include "libre3/nfcmetadata.hpp"
#define NOLOG
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
using namespace std;
constexpr uint32_t oldStart=1700000000, newStart=1800000000;
constexpr auto oldAddress="01:02:03:04:05:06", newAddress="11:12:13:14:15:16";
int creates=0, authSends=0, resends=0;
bool orphan=false;
struct Info {
 uint32_t starttime=oldStart,pin=99,lastscantime=0,pollcount=0,scancount=0,endhistory=0,lockcount=9;
 uint16_t warmup2=60,wearduration2=20160,lastLifeCountReceived=999,lastHistoricLifeCountReceivedPos=44;
 bool haskAuth=true;
 int patchState=4,streamingIsEnabled=1;
 char deviceaddress[18]="01:02:03:04:05:06";
};
Info created;
struct SensorGlucoseData {
 using longsensorname_t=array<char,16>;
 Info info;
 Info* getinfo(){return &info;}
 char* deviceaddress(){return info.deviceaddress;}
 uint32_t getstarttime(){return info.starttime;}
 int getweardurationMIN(){return info.wearduration2;}
 static void mkdatabase3(const string&,uint32_t start,uint32_t pin,const char* address,uint16_t warmup,uint16_t wear){
   ++creates;
   if(orphan)return;
   created=Info{};created.starttime=start;created.pin=pin;
   strcpy(created.deviceaddress,address);created.warmup2=warmup;created.wearduration2=wear;
 }
};
struct sensor {
 string name;uint32_t starttime=oldStart,endtime=1;
 int finished=2,halfdays=28;bool initialized=false;
 const char* showsensorname(){return name.c_str();}
 void markRemovedByUser(){finished=2;}
};
struct pathconcat:string {
 pathconcat(const string& base,const SensorGlucoseData::longsensorname_t& name):string(base+string(name.data(),name.size())){}
};
void resensordata(int){++resends;}
struct Sensoren {
 sensor slots[2];SensorGlucoseData data[2];string inbasedir="synthetic/";
 sensor* sensorlist(){return slots;}
 sensor* findsensorm(string_view name){for(auto &s:slots)if(s.name==name)return &s;return nullptr;}
 SensorGlucoseData* getSensorData(int i){return &data[i];}
 void sendKAuth(SensorGlucoseData*){++authSends;}
 int addsensor(string_view name){slots[1].name=name;slots[1].finished=0;data[1].info=created;return 1;}
 sensor* getsensor(int i){return &slots[i];}
'''
code += method('  static SensorGlucoseData::longsensorname_t\n  namelibre3') + '\n'
code += method('  int makelibre3sensorindex(')
code += r'''
};
Sensoren known(){
 Sensoren s;auto key=s.namelibre3("TEST00001");
 s.slots[0].name=string(key.data(),key.size());return s;
}
int scan(Sensoren &s,uint32_t start=newStart,const char* address=newAddress,uint32_t pin=42,bool nfc=true){
 return s.makelibre3sensorindex("TEST00001",start,pin,address,newStart+10,60,20160,nfc);
}
int main(){
 {auto s=known();assert(scan(s,newStart,newAddress,0)==0);
 auto &i=s.data[0].info;
 assert(i.starttime==newStart && string(i.deviceaddress)==newAddress && i.pin==0 && !i.haskAuth);
 assert(i.lastLifeCountReceived==1 && i.lastHistoricLifeCountReceivedPos==0 && i.lockcount==0);
 assert(i.patchState==0 && i.streamingIsEnabled==0);
 assert(s.slots[0].finished==0 && s.slots[0].starttime==newStart && s.slots[0].endtime==0);
 assert(i.lastscantime==newStart+10);
 assert(scan(s)==0 && i.starttime==newStart);}
 // A different serial never takes the known-record path, even after removal.
 {auto s=known();auto key=s.namelibre3("TEST00000");s.slots[0].name=string(key.data(),key.size());
 int before=creates;assert(scan(s)==1 && creates==before+1);
 assert(s.data[0].info.starttime==oldStart && s.slots[0].finished==2);
 assert(s.data[1].info.starttime==newStart && string(s.data[1].info.deviceaddress)==newAddress);}
 // Recover a retained empty directory even if its roster entry is absent.
 {Sensoren s;orphan=true;created=Info{};assert(scan(s)==1);
 assert(s.data[1].info.starttime==newStart && string(s.data[1].info.deviceaddress)==newAddress);orphan=false;}
 // Never repurpose records with any form of history. Check no partial mutation.
 for(int kind=0;kind<3;++kind){auto s=known();auto &i=s.data[0].info;
 if(kind==0)i.pollcount=25;if(kind==1)i.scancount=1;if(kind==2)i.endhistory=2;
 const auto before=i;int sends=authSends;
 assert(scan(s)==-1 && memcmp(&i,&before,sizeof i)==0 && sends==authSends && s.slots[0].finished==2);}
 {Sensoren s;orphan=true;created=Info{};created.pollcount=25;
 assert(scan(s)==-1 && s.slots[1].finished==2 && s.data[1].info.starttime==oldStart);orphan=false;}
 // A normal active rescan retains history/cursors, including an adjusted start.
 {auto s=known();auto &i=s.data[0].info;i.pollcount=25;
 assert(scan(s,oldStart+80,oldAddress,0)==0);
 assert(i.starttime==oldStart && i.pollcount==25 && i.lastLifeCountReceived==999 && i.pin==0);}
 // Same activation, refreshed address: accept NFC credentials without clearing history.
 {auto s=known();s.data[0].info.pollcount=25;
 assert(scan(s,oldStart)==0 && s.data[0].info.pollcount==25 && string(s.data[0].info.deviceaddress)==newAddress);}
 // Non-NFC callers do not acquire authority to rewrite activation metadata.
 {auto s=known();assert(scan(s,newStart,newAddress,42,false)==0);
 assert(s.data[0].info.starttime==oldStart && string(s.data[0].info.deviceaddress)==oldAddress);}
 {auto s=known();auto before=s.data[0].info;assert(scan(s,newStart+11)==-1);
 assert(memcmp(&before,&s.data[0].info,sizeof before)==0);
 assert(scan(s,newStart,"bad")==-1);}
 {Info i;auto before=i;libre3nfc::NfcMetadata larger{newStart,42,newAddress,60,21600};
 assert(libre3nfc::refreshNfcMetadata(i,larger)==libre3nfc::NfcRefresh::storageConflict);
 assert(memcmp(&before,&i,sizeof i)==0);}
 // Parse only complete successful responses, including the supported A5 padding.
 {array<uint8_t,29> a{};a[1]=0xA5;
 assert(libre3nfc::nfcPayloadOffset(a.data(),a.size(),26)==3);
 for(size_t size=0;size<a.size();++size)assert(libre3nfc::nfcPayloadOffset(a.data(),size,26)==size);
 a[0]=1;assert(libre3nfc::nfcPayloadOffset(a.data(),a.size(),26)==a.size());
 a.fill(0xA5);a[0]=0;assert(libre3nfc::nfcPayloadOffset(a.data(),a.size(),26)==a.size());
 array<uint8_t,30> padded{};padded[1]=padded[2]=0xA5;
 assert(libre3nfc::nfcPayloadOffset(padded.data(),padded.size(),26)==4);}
 cout<<"PASS: NFC creation, retry, remove/re-add, orphan directory, history protection, credentials, bounds and caller isolation\n";
}
'''
with tempfile.TemporaryDirectory(prefix='libre3-nfc-test-') as directory:
    path = Path(directory)
    (path/'test.cpp').write_text(code)
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror',
                    '-Wno-misleading-indentation','-fsanitize=address,undefined',
                    '-fno-omit-frame-pointer','-I',str(root/'Common/src/main/cpp'),
                    str(path/'test.cpp'),'-o',str(path/'test')],check=True)
    subprocess.run([str(path/'test')],check=True)
