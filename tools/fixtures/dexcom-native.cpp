// Android/JNI/storage stand-ins for the production methods injected by the runner.
#include "dexcom/ScanIdentity.hpp"
#include <cassert>
#include <cstring>
#include <ctime>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <vector>
#include <unistd.h>
using namespace std;
#define LOGGER(...) ((void)0)
#define LOGAR(...) ((void)0)
constexpr int maxdays=46, maxminutes=45*24*60, maxSIhours=24*24,youngsensorsecs=7200;
constexpr int DEXSECONDS=300, maxbluetoothage=300, interval5=300;
struct pathconcat : string {
    pathconcat(string_view base, string_view leaf):string(string(base)+"/"+string(leaf)){}
    pathconcat(string_view base,const array<char,16>& leaf):pathconcat(base,string_view(leaf.data(),16)){}
    operator const char*() const {return c_str();}
};
struct Info {
    uint32_t starttime=1700000001, lastscantime=0;
    int32_t starthistory=0,endhistory=0;
    uint32_t scancount=0;
    uint16_t startid=0,interval=300;
    uint8_t dupl=3;
    bool dexcom=true;
    uint8_t days=12;
    uint16_t warmup=30,wearduration=14400,lastLifeCountReceived=1;
    int pollcount=0;
    bool sibionics=false,notchinese=false;
    uint16_t wearduration2=0;
    uint32_t siIdlen=0;
    char8_t siId[68]{};
    char DexDeviceName[10]{};
    array<uint8_t,16> sharedKey{};
};
map<string,Info> disk;
int mkdir(const char*,int){return 0;}
int access(const char* path,int){return disk.contains(path)?0:-1;}
void writeall(const char* path,const void* info,size_t){disk[path]=*static_cast<const Info*>(info);}
template<class T> struct Readall {
    Info info;
    Readall(const char* path):info(disk.at(path)){}
    const uint8_t* data(){return reinterpret_cast<const uint8_t*>(&info);}
    size_t size(){return sizeof(Info);}
};
struct Stream {uint32_t timestamp;uint32_t gettime(){return timestamp;}};
struct SensorGlucoseData {
    Info metadata;
    string sensordir;
    bool haserror=false,sensorerror=false,missingInfo=false;
    uint32_t sensorErrorTime=0;
    int saved=0,lastSaved=-1,broadcast=10000;
    array<Stream,4896> stream{};
    static constexpr const char* infopdat="info.dat";
    explicit SensorGlucoseData(string path="sensor1234"):sensordir(std::move(path)){}
    Info* getinfo(){return missingInfo?nullptr:&metadata;}
    const Info* getinfo()const{return missingInfo?nullptr:&metadata;}
    bool isDexcom()const{return getinfo()->dexcom;}
    bool isSibionics()const{return metadata.sibionics;}
    bool isSibionics1()const{return false;}
    bool isLibre2()const{return false;}bool isLibre3()const{return false;}bool isAiDex()const{return false;}
    bool isAccuChek()const{return false;}bool isAir()const{return false;}
    int siSubtype()const{return 0;}uint32_t lastused()const{return stream[0].timestamp;}
    int getelsize()const{return 12;}
    int perhour()const{return 12;}int streamperhour()const{return 12;}
    array<char,11> dummyName{};
    const array<char,11>* shortsensorname()const{return &dummyName;}
    int getWarmupSEC()const{return metadata.warmup*60;}
    uint32_t getstarttime()const{return metadata.starttime;}
    int pollcount()const{return metadata.pollcount;}
    Stream* getstream(int index){assert(index>=0&&index<int(stream.size()));return &stream[index];}
    template<int Interval> void savepollallIDs(uint32_t t,int index,int,int,float){
        assert(index>=0&&index<int(stream.size()));
        saved++;lastSaved=index;stream[index].timestamp=t;
        metadata.pollcount=max(metadata.pollcount,index+1);
    }
    void saveDexFuture(int,uint32_t,int){}void consecutivelifecount(){}
    int getbroadcastfrom(){return broadcast;}void setbroadcastfrom(int v){broadcast=v;}
    void backstream(int){}void fastupdatelifecount(int v){metadata.lastLifeCountReceived=v;}
    /* SENSOR_METHODS */
};
struct sensor {
    char name[17]{};
    bool present=true,finished=false,initialized=false;
    int halfdays=24;
    uint32_t starttime=1700000001,endtime=0;
    const char* showsensorname(){return name;}
};
void resensordata(int){}
struct Sensoren {
    string inbasedir="sensors";
    static constexpr string_view manualDexcomPrefix=dexcom::manualPrefix;
    array<sensor,32> records{};
    vector<unique_ptr<SensorGlucoseData>> data;
    int last(){return int(data.size())-1;}
    sensor* getsensor(int i){return &records.at(i);}
    sensor* sensorlist(){return records.data();}
    SensorGlucoseData* getSensorData(int i){return data.at(i).get();}
    sensor* findsensorm(string_view name){
        for(int i=0;i<=last();i++)if(string_view(records[i].name,16)==name)return getsensor(i);
        return nullptr;
    }
    void removeunused(){}void sendKAuth(SensorGlucoseData*){}void sendsiScan(SensorGlucoseData*){}
    void setstreaming(SensorGlucoseData*){}void setindices(){}
    int addsensor(const array<char,16>& name){
        int i=int(data.size());copy(name.begin(),name.end(),records[i].name);
        pathconcat path(inbasedir,name);
        auto d=make_unique<SensorGlucoseData>(path);
        d->metadata=disk.at(string(path)+"/info.dat");
        d->initialHistoryBytes();data.push_back(std::move(d));return i;
    }
    /* REGISTRY_METHODS */
};
Sensoren registry;
Sensoren* sensors=&registry;
struct updateone {static constexpr int sendstream=1;};
struct Backup {static constexpr int wakestream=1;void resendResetDevices(const int*){}void wakebackup(int){}};
Backup backupInstance;Backup* backup=&backupInstance;
using jlong=long long;using jclass=int;
int rate2changeindex(float){return 0;}
jlong glucoseback(uint32_t,uint32_t,float,SensorGlucoseData*){return 123;}
void wakewithcurrent(){}
struct Cmd {int start,end;};
using jbyteArray=shared_ptr<Cmd>;
struct JNIEnv{};
struct dexcomstream {SensorGlucoseData* hist;};
jbyteArray mkbackfillcmd(JNIEnv*,int start,int end){return make_shared<Cmd>(Cmd{start,end});}
/* DRIVER_METHODS */

string barcode(string_view model="4574",string_view serial="ABC123456789"){
    return "\x1d" "010038627000"+string(model)+"21"+string(serial)+"\x1d" "2400012";
}
string manual(int days=10,string_view serial="M00000000001"){
    return string(dexcom::manualPrefix)+string(serial)+(days==15?"15000000000000000":"00000000000000000")+"2400012";
}
void payload(Info& info,const string& text){assert(text.size()<=sizeof(info.siId));info.siIdlen=text.size();memcpy(info.siId,text.data(),text.size());}
void live(SensorGlucoseData& d,int seconds,int age=0){
    glucoseinput value{};value.secsSinceStart=seconds;value.age=age;value.mgdL=100;value.predictedmgdL=110;
    jlong timeres[]{(jlong(d.getstarttime())+seconds)*1000,0};
    value.actual(&d,timeres,0);
    assert((timeres[1]!=0)==!d.sensorerror);
}
int main(){
    for(auto model:{"4574","4581","0071"}){
        auto scan=dexcom::parseScan(barcode(model));assert(scan);
        assert(scan->days==(string_view(model)=="0071"?10:15));
        assert(string(scan->name.data(),16)=="ABC1234567890012");
        assert(scan->payload.size()<=68);
        assert(dexcom::parseScan(scan->payload)->name==scan->name);
    }
    auto canonical=*dexcom::parseScan(barcode());
    auto labelled=dexcom::parseScan("(01)00386270004574(11)260101(17)280101(10)BATCH(21)ABC123456789(240)0012");
    assert(labelled&&labelled->name==canonical.name&&labelled->days==15);
    auto separated=dexcom::parseScan("]d2^]0100386270004574112601011728010110BATCH^]21ABC123456789^]2400012");
    assert(separated&&separated->name==canonical.name);
    // Existing 55-byte scans retain serial/PIN identity when dates follow the serial.
    const string old55="\x1d" "010038627000457421ABC123456789\x1d" "11260101172801012400012";
    assert(old55.size()==55);
    auto legacyFull=dexcom::parseScan(old55);
    assert(legacyFull&&legacyFull->name==canonical.name&&legacyFull->days==15);
    assert(dexcom::parseScan(barcode("4574","112401725021")));
    auto shortSerial=dexcom::parseScan(barcode("4574","ABC"));assert(shortSerial);
    assert(string(shortSerial->name.data(),16)=="ABC2700045740012");
    for(auto invalid:{barcode()+"\x1d" "2409999",barcode("4574","ABCDEFGHIJKLM"),
            "(01)00386270004574(21)ABC(240)12A4"s,"(01)99999999994574(21)ABC(240)0012"s,
            "(01)00386270004574(21)(240)0012"s,barcode().substr(0,barcode().size()-1),
            string(513,'0'),"(01)00386270004574(21)ABC(99)UNKNOWN(240)0012"s}){
        assert(!dexcom::parseScan(invalid));
    }
    for(int days:{10,15}){
        auto scan=dexcom::parseScan(manual(days));assert(scan&&scan->manual&&scan->days==days);
        assert(string(scan->name.data(),16)=="M000000000010012");
        assert(scan->payload==manual(days));
        auto [id,d]=registry.makeDexComSensorindex(*scan,1700000001);
        assert(d&&d->metadata.days==days+2&&d->getDexWearMinutes()==days*1440);
        assert(registry.getsensor(id)->halfdays==(days+2)*2);
        // Mark it bound so a new random identity with this PIN cannot replace it.
        d->metadata.DexDeviceName[0]='D';
    }
    // Interrupted manual setup can be reused only for the same model, while still empty/unbound.
    registry=Sensoren{};
    auto empty10=dexcom::parseScan(manual());auto [emptyId,empty]=registry.makeDexComSensorindex(*empty10,1700000001);
    assert(registry.makeDexComSensorindex(*dexcom::parseScan(manual(10,"M00000000002")),1700000002).first==emptyId);
    auto [newId,new15]=registry.makeDexComSensorindex(*dexcom::parseScan(manual(15,"M00000000003")),1700000002);
    assert(newId!=emptyId&&new15->getDexWearMinutes()==21600);
    empty->metadata.sharedKey[0]=42;
    assert(registry.makeDexComSensorindex(*dexcom::parseScan(manual(10,"M00000000004")),1700000003).first!=emptyId);
    for(int binding=0;binding<5;binding++){
        registry=Sensoren{};
        auto [id,d]=registry.makeDexComSensorindex(*empty10,1700000001);
        switch(binding){
            case 0:d->metadata.pollcount=1;break;
            case 1:d->metadata.scancount=1;break;
            case 2:d->metadata.endhistory=1;break;
            case 3:d->metadata.DexDeviceName[0]='D';break;
            case 4:d->metadata.sharedKey[0]=42;break;
        }
        assert(registry.makeDexComSensorindex(*dexcom::parseScan(manual(10,"M00000000002")),1700000002).first!=id);
    }

    // An old compact barcode record had a byte-offset directory name. Keep it and its key/history.
    registry=Sensoren{};
    const array<char,16> oldName{'L','E','G','A','C','Y','0','0','0','0','0','0','0','0','1','2'};
    pathconcat oldPath(registry.inbasedir,oldName);
    assert(SensorGlucoseData::mkdatabaseDex(oldPath,barcode("4574","ABC"),1700000001));
    int legacyId=registry.addsensor(oldName);auto* legacy=registry.getSensorData(legacyId);
    legacy->metadata.pollcount=100;legacy->metadata.sharedKey[0]=42;legacy->stream[99].timestamp=1700029701;
    assert(legacy->getDexWearMinutes()==21600&&legacy->metadata.days==17);
    auto [rescannedId,rescanned]=registry.makeDexComSensorindex(*shortSerial,1700000002);
    assert(rescannedId==legacyId&&rescanned==legacy&&registry.data.size()==1);
    assert(legacy->metadata.sharedKey[0]==42&&legacy->metadata.pollcount==100);
    assert(legacy->stream[99].timestamp==1700029701&&legacy->sensordir==oldPath);

    for(int days:{10,15}){
        SensorGlucoseData d;payload(d.metadata,manual(days));
        assert(d.initialHistoryBytes()==17*24*12*12);
        assert(d.pollStorageSize()==17*24*12);
        assert(d.getDexWearMinutes()==days*1440);
        assert(d.getmaxdexcount()==(days==10?3025:4465));
        assert(d.getDexMaxSecs()==(days==10?907500:1339500));
        assert(d.officialendtime()==d.getstarttime()+days*86400);
        assert(d.expectedEndTime()==d.getstarttime()+days*86400+43200);
        d.stream[0].timestamp=1700000001;
        d.metadata.pollcount=3025;
        assert(d.hasData(1700000002)==(days==15));
        d.metadata.pollcount=d.getmaxdexcount();assert(!d.hasData(1700000002));
        d.metadata.pollcount=1;
        live(d,10*86400+13*3600);
        assert((d.saved==1)==(days==15));
        live(d,d.getDexMaxSecs());assert(!d.sensorerror&&d.lastSaved==d.getmaxdexcount());
        int saved=d.saved;live(d,d.getDexMaxSecs()+1);assert(d.sensorerror&&d.saved==saved);
        live(d,1799);assert(d.sensorerror&&d.saved==saved);live(d,1800);assert(!d.sensorerror);
        // Backfill stores beyond day ten use the 15-day map, without altering five-minute indexing.
        dexbackfill back{};back.secsSinceStart=15*86400;back.mgdL=100;back.type=6;
        saved=d.saved;back.backfill(&d);assert((d.saved==saved+1)==(days==15));
        d.metadata.starttime=uint32_t(time(nullptr))-d.getDexMaxSecs()-600;
        d.metadata.pollcount=1;d.metadata.lastLifeCountReceived=0;
        dexcomstream transport{&d};auto cmd=getDexbackfillcmd(nullptr,0,reinterpret_cast<jlong>(&transport));
        assert(cmd&&cmd->start==300&&cmd->end==d.getDexMaxSecs());
        d.metadata.lastLifeCountReceived=d.getmaxdexcount();
        assert(!getDexbackfillcmd(nullptr,0,reinterpret_cast<jlong>(&transport)));
    }
    SensorGlucoseData corrupt;corrupt.metadata.siIdlen=255;corrupt.initialHistoryBytes();
    assert(corrupt.getDexWearMinutes()==14400);
    SensorGlucoseData missing;missing.missingInfo=true;
    assert(missing.initialHistoryBytes()==0&&missing.haserror);
    assert(missing.pollStorageSize()==24*24*60);
    assert(!SensorGlucoseData::mkdatabaseDex("bad",string(69,'a'),1700000001));
    assert(!SensorGlucoseData::mkdatabaseDex("bad",manual(),1700000001,12));
    cout<<"PASS: Dexcom GS1/manual identities, model-aware reuse, legacy migration, storage, live cutoff and backfill\n";
}
