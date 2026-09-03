/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*      Fri Nov 21 11:08:14 CET 2025                                                 */


constexpr const int givefirst=0;
//#define TEST
/*
#define xquotes(s) quotes(s)
#define quotes(s) #s

#define SIDE xquotes(NSIDE) */
#define LOGGERICE(...) LOGGER("ICE: " __VA_ARGS__)
#define LOGARICE(...) LOGAR("ICE: " __VA_ARGS__)

#include "datbackup.hpp"
#include "libjuice/include/juice/juice.h"
#include <unistd.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <algorithm>
#include <thread>
#include <array>
#include <bitset>
#include <assert.h>
#include <semaphore>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "logs.hpp"
#include "ContextHTTPS.hpp"
#include "ElapsedRealtime.hpp"
using namespace std::literals;
#include "Agent_data.hpp"
#include "BackDescription.hpp"
#include "inout.hpp"
#include  "udp_header.h"
#include "destruct.hpp"
#include "PlaceBuf.hpp"
#include "ICEConnect.hpp"
#include "net/makerandom.hpp"

extern std::mutex turn_server_mutex;

constexpr const int maxconnectionunused=24*60*60;
extern uint32_t getConnectTime(const int allindex);
extern void setConnectTime(const int allindex,uint32_t tim);
constexpr const std::string_view hostnames[]{
//List of hostnames to Juggluco connect: https://github.com/j-kaltes/jugglucoconnect
#include "jugglucoconnect.h"
};
constexpr int nrhostnames=std::size(hostnames);
#include <zlib.h>
constexpr const uLong hashfunc(const char *d, int len) {
    return crc32(0,(const unsigned char*)d,len);
    }
int hostselect(std::string_view name) {
    int hash=hashfunc(name.data(),name.size())%nrhostnames;
    LOGGERICE("hostselect(%.*s)=%d %.*s\n",name.data(),name.data(),hash,hostnames[hash].size(),hostnames[hash].data());
    return hash;
    }
static std::mutex ice_config_mutex;
static ICEConfigSnapshot ice_config;

ICEConfigSnapshot currentICEConfig() {
    const std::lock_guard<std::mutex> lock(ice_config_mutex);
    return ice_config;
    }

void updateICEConfig(std::string rendezvousHost, uint16_t rendezvousPort,
                     bool useTurnForStun, bool verifyRendezvousCertificate) {
    const std::lock_guard<std::mutex> lock(ice_config_mutex);
    ice_config.rendezvousHost=std::move(rendezvousHost);
    ice_config.rendezvousPort=rendezvousPort?rendezvousPort:6789;
    ice_config.useTurnForStun=useTurnForStun;
    ice_config.verifyRendezvousCertificate=verifyRendezvousCertificate;
    }

RendezvousEndpoint resolveRendezvousEndpoint(std::string_view label) {
    auto config=currentICEConfig();
    if(config.rendezvousHost.empty())
        config.rendezvousHost=std::string(hostnames[hostselect(label)]);
    return {std::move(config.rendezvousHost),config.rendezvousPort,
            config.verifyRendezvousCertificate};
    }

ICEConnect::ICEConnect(int allindex,const passhost_t &host)
    :Connect(allindex),side(host.side) {
    auto endpoint=resolveRendezvousEndpoint(host.getICEname());
    rendezvousHost=std::move(endpoint.host);
    rendezvousPort=endpoint.port;
    verifyRendezvousCertificate=endpoint.verifyCertificate;
    agent.store(nullptr);
    }
#ifndef LOGGER
#define LOGGER(...) fprintf(stderr,__VA_ARGS__)
#endif
#define BUFFER_SIZE 4096

#define JUICE_ERR_SUCCESS 0

static bool stillworking(int allindex)  {
    ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
    bool res=con&&!con->finish&&con->allindex==allindex;
    if(!res)  {
        LOGGERICE("stillworking(%d)=%d\n",allindex,res);
        }
   else {
        return getBackupHosts()[allindex].ICE;
        }
    return res;
    }

static ICEConnect *currentICEConnection(int allindex, juice_agent_t *agent) {
    if(!stillworking(allindex))
        return nullptr;
    ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
    if(!con||!con->isCurrentAgent(agent)) {
        LOGGERICE("Ignoring stale agent callback allindex=%d agent=%p\n",allindex,agent);
        return nullptr;
        }
    return con;
    }

static bool waitForCurrentAgent(ICEConnect *con, juice_agent_t *agent, int seconds) {
    const int64_t deadline=elapsedRealtimeMilliseconds()+
                           static_cast<int64_t>(std::max(seconds,0))*1000;
    while(con->isCurrentAgent(agent)&&!con->endConnect.load()) {
        const int64_t remaining=deadline-elapsedRealtimeMilliseconds();
        if(remaining<=0)
            break;
        std::this_thread::sleep_for(
            std::chrono::milliseconds(std::min<int64_t>(remaining,250)));
        }
    return con->isCurrentAgent(agent)&&!con->endConnect.load();
    }

static HTTPSRequestOptions rendezvousRequestOptions(ICEConnect *con) {
    return {
        .timeoutMilliseconds=10000,
        .cancelled=con->currentRendezvousCancellation(),
        .verifyCertificate=con->verifyRendezvousCertificate
        };
    }

bool ICEConnect::prepareRendezvousGeneration() {
    const std::lock_guard<std::mutex> lock(rendezvousGenerationMutex);
    std::array<unsigned char,16> random{};
    if(!makerandom(random.data(),random.size()))
        return false;
    static constexpr char hex[]="0123456789abcdef";
    rendezvousGeneration.resize(random.size()*2);
    for(size_t index=0;index<random.size();++index) {
        rendezvousGeneration[index*2]=hex[random[index]>>4];
        rendezvousGeneration[index*2+1]=hex[random[index]&0x0f];
    }
    return true;
}

std::string ICEConnect::currentRendezvousGeneration() {
    const std::lock_guard<std::mutex> lock(rendezvousGenerationMutex);
    return rendezvousGeneration;
}

static void wakeCloneSender(const passhost_t &host,uintptr_t reason);

static bool restartRejectedNegotiation(ICEConnect *con,juice_agent_t *agent,
                                       uint64_t generation,int allindex,
                                       const char *reason) {
    if(!con->requestReconnectIfCurrent(agent,generation))
        return false;
    const passhost_t &host=getBackupHosts()[allindex];
    LOGGERICE("%s %d: restart rejected negotiation: %s\n",
              host.getICEname().data(),host.side,reason);
    wakeCloneSender(host,wakeall|wakereconnect);
    {
    std::lock_guard<std::mutex> lock(con->receiveThreadMutex);
    con->wakeReceiver=true;
    }
    con->receiveThreadCon.notify_one();
    return true;
    }
const char *juiceErrorString(int error) {
    switch(error) {
        case JUICE_ERR_SUCCESS : return "success";
        case JUICE_ERR_INVALID: return "invalid argument";
        case JUICE_ERR_FAILED: return "runtime error";
        case JUICE_ERR_NOT_AVAIL: return "element not available";
        case JUICE_ERR_IGNORED: return "ignored";
        case JUICE_ERR_AGAIN: return "buffer full";
        case JUICE_ERR_TOO_LARGE: return "datagram too large";
        default: return "Unknown error";
        };
 }


static void on_state_changed1(juice_agent_t *agent, juice_state_t state, void *user_ptr);

static void on_candidate1(juice_agent_t *agent, const char *sdp, void *user_ptr);

static void on_gathering_done1(juice_agent_t *agent, void *user_ptr);

static void on_recv1(juice_agent_t *agent, const char *data, size_t size, void *user_ptr);

class CreateAgentData {
public:
   CreateAgentData(std::string_view commonLabel,bool side,const char *sdp,int sdplen=-1):
    agent(Agent_data::newAgent('0'+side,commonLabel,{sdp,(sdplen==-1?strlen(sdp):sdplen)})) {
            };
    ~CreateAgentData() {
        Agent_data::deleteAgent(agent);
        }
    Agent_data *agentdata() {
        return agent;
        }
    const char *data() const {
        return reinterpret_cast<const char*>(agent);
        }
     int size() const {
        return agent->datalen();
        }
      std::span<const char> getSpan() const {
        return {data(),size_t(size())};
        }
     operator std::span<const char>() const {
        return getSpan();
        }
private:
    Agent_data *agent;
    };
//static bool gathering_done=false;

static bool isGenerationToken(std::string_view token) {
    if(token.size()!=32)
        return false;
    return std::all_of(token.begin(),token.end(),[](unsigned char value) {
        return (value>='0'&&value<='9')||(value>='a'&&value<='f');
    });
}

static std::optional<std::string> generationResponseToken(
        const std::vector<char> &body) {
    constexpr size_t expected=sizeof(BackDescription)+32+1;
    if(body.size()!=expected)
        return std::nullopt;
    const auto *response=reinterpret_cast<const BackDescription *>(body.data());
    const std::string_view token(response->description,32);
    if(response->description[32]!='\0'||!isGenerationToken(token))
        return std::nullopt;
    return std::string(token);
}

static void watchPeerGeneration(
        juice_agent *agent,int allindex,uint64_t agentGeneration,
        std::string commonLabel,bool side,std::string hostname,
        uint16_t rendezvousPort,std::string localGeneration,
        std::shared_ptr<const std::atomic_bool> cancellation) {
    static constexpr std::string_view path="/generation";
    std::string observedPeer;
    int transientErrors=0;
    while(true) {
        ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
        if(!con||!con->isCurrentAgent(agent,agentGeneration)||
           con->endConnect.load()||
           (cancellation&&cancellation->load(std::memory_order_acquire)))
            return;
        std::string requestGeneration=localGeneration;
        if(!observedPeer.empty()) {
            requestGeneration.push_back(':');
            requestGeneration.append(observedPeer);
        }
        CreateAgentData request(commonLabel,side,requestGeneration.data(),
                                static_cast<int>(requestGeneration.size()));
        auto [body,code]=ContextHTTPS::getContext().putRequest(
            hostname,rendezvousPort,path,request.getSpan(),{},
            {.timeoutMilliseconds=50000,
             .cancelled=cancellation,
             .verifyCertificate=con->verifyRendezvousCertificate});
        if(!con->isCurrentAgent(agent,agentGeneration)||
           con->endConnect.load()||
           (cancellation&&cancellation->load(std::memory_order_acquire)))
            return;
        if(code==400) {
            con->generationWatchCapability.store(-1,std::memory_order_release);
            LOGARICE("peer generation watch unsupported");
            return;
        }
        if(code!=200) {
            if(++transientErrors>=3)
                return;
            if(!waitForCurrentAgent(con,agent,2))
                return;
            continue;
        }
        con->generationWatchCapability.store(1,std::memory_order_release);
        transientErrors=0;
        const auto peer=generationResponseToken(body);
        if(!peer)
            continue;
        if(observedPeer.empty()) {
            observedPeer=*peer;
            continue;
        }
        if(*peer==observedPeer)
            continue;
        LOGARICE("peer generation changed");
        restartRejectedNegotiation(con,agent,agentGeneration,allindex,
                                   "peer generation changed");
        return;
    }
}

static void publishRendezvousGeneration(
        juice_agent *agent,int allindex,uint64_t agentGeneration,
        std::string commonLabel,bool side,std::string hostname,
        uint16_t rendezvousPort,std::string localGeneration,
        bool verifyCertificate,
        std::shared_ptr<const std::atomic_bool> cancellation) {
    static constexpr std::string_view path="/generation";
    CreateAgentData request(commonLabel,side,localGeneration.data(),
                            static_cast<int>(localGeneration.size()));
    auto [body,code]=ContextHTTPS::getContext().putRequest(
        hostname,rendezvousPort,path,request.getSpan(),{},
        {.timeoutMilliseconds=10000,
         .cancelled=cancellation,
         .verifyCertificate=verifyCertificate});
    ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
    if(!con||!con->isCurrentAgent(agent,agentGeneration)||
       con->endConnect.load()||
       (cancellation&&cancellation->load(std::memory_order_acquire)))
        return;
    if(code==400) {
        con->generationWatchCapability.store(-1,std::memory_order_release);
        LOGARICE("peer generation watch unsupported");
        return;
    }
    if(code==200) {
        con->generationWatchCapability.store(1,std::memory_order_release);
        LOGARICE("published rendezvous generation");
        return;
    }
    LOGGERICE("publish rendezvous generation failed code=%d\n",code);
}

static void startPeerGenerationWatch(
        ICEConnect *con,juice_agent *agent,int allindex,
        std::string_view commonLabel,bool side,std::string_view hostname,
        uint16_t rendezvousPort) {
    if(con->generationWatchCapability.load(std::memory_order_acquire)<0)
        return;
    const std::string localGeneration=con->currentRendezvousGeneration();
    if(!isGenerationToken(localGeneration))
        return;
    const uint64_t agentGeneration=con->currentAgentGeneration();
    auto cancellation=con->beginGenerationWatch();
    if(side==givefirst) {
        std::thread(publishRendezvousGeneration,agent,allindex,agentGeneration,
                    std::string(commonLabel),side,std::string(hostname),
                    rendezvousPort,localGeneration,
                    con->verifyRendezvousCertificate,
                    std::move(cancellation)).detach();
        }
    else {
        std::thread(watchPeerGeneration,agent,allindex,agentGeneration,
                    std::string(commonLabel),side,std::string(hostname),
                    rendezvousPort,localGeneration,std::move(cancellation)).detach();
        }
}

static void getAddressesThread(juice_agent *agent,int allindex,uint64_t generation,
                               std::string commonLabel,bool side,std::string hostname,
                               uint16_t rendezvousPort) {
   static std::string_view address{"/address"};
   CreateAgentData addressdata(commonLabel,side,"") ;
   int errors=0;
   while(errors<5) {
            ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
            if(!con||!con->isCurrentAgent(agent,generation))
                return;
            LOGGERICE("getaddress %s %d\n",commonLabel.data(),side);
            auto [resbody,code]=ContextHTTPS::getContext().getRequest(
                hostname,rendezvousPort,address,addressdata.getSpan(),{},
                rendezvousRequestOptions(con));
            if(!con->isCurrentAgent(agent,generation))
                return;
            switch(code) {
                case 200: {
                if(resbody.size()>= (sizeof(BackDescription )+20)) {
                    errors=0;
                    const BackDescription *other=reinterpret_cast<const BackDescription *>(resbody.data());
                    #ifndef NOLOG
                    int res=
                    #endif
                    juice_add_remote_candidate(agent, other->description);
                    LOGGERICE("%s %d: getaddress %s res=%d\n",commonLabel.data(),side,other->description,res);
                    }
                 else {
                    juice_set_remote_gathering_done(agent);
                    LOGGERICE("getaddress %s %d: juice_set_remote_gathering_done\n",commonLabel.data(),side);
                    return;
                    }
                };break;
              case 400: {
                LOGGERICE("getaddress %s %d: ERROR try again\n",commonLabel.data(),side);
                ++errors;
                if(!waitForCurrentAgent(con,agent,2))
                    return;
                  };break;
              default: {
                LOGGERICE("getaddress %s %d: Http error\n",commonLabel.data(),side);
                ++errors;
                if(!waitForCurrentAgent(con,agent,10))
                    return;
                };break;
                };
          }
      LOGGERICE("getaddress %s %d: end thread\n",commonLabel.data(),side);
      ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
      if(!con)
          return;
      restartRejectedNegotiation(con,agent,generation,allindex,
                                 "remote candidate stream unavailable");
     } 



static void on_candidate1(juice_agent_t *agent, const char *sdp, void *user_ptr) {
   const int allindex=(int)(long)user_ptr;
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con)
        return;
   const uint64_t generation=con->currentAgentGeneration();
   const passhost_t &host= getBackupHosts()[allindex];
   const std::string commonLabel(host.getICEname());
   const bool side=host.side;
   const std::string hostname(con->rendezvousHost);
   const uint16_t rendezvousPort=con->rendezvousPort;
   static std::string_view address{"/address"};
   CreateAgentData sdpdata(commonLabel,side,sdp) ;
   for(int i=0;i<20;++i) {
       if(!con->isCurrentAgent(agent,generation))
           return;
       auto [resbody,code]=ContextHTTPS::getContext().putRequest(
           hostname,rendezvousPort,address,
           std::span((const char *)sdpdata.data(),sdpdata.size()),{},
           rendezvousRequestOptions(con));
       if(!con->isCurrentAgent(agent,generation))
           return;
       if(code==200) {
             LOGGERICE( "putaddress %s %d: success: %s\n",commonLabel.data(),side, sdp);
            break;
            }
       if(code==400) {
            LOGGERICE( "putaddress %s %d: failed: %s\n",commonLabel.data(),side, sdp);
            restartRejectedNegotiation(con,agent,generation,allindex,
                                       "local candidate rejected");
            return;
            }
      LOGGERICE("putaddress %s %d: ERROR: %s\n",commonLabel.data(),side,  sdp);
      if(!waitForCurrentAgent(con,agent,20))
          return;
      }
   }


// Agent 1: on local candidates gathering done
static void on_gathering_done1(juice_agent_t *agent, void *user_ptr) {
   const int allindex=(int)(long)user_ptr;
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con)
        return;
    const uint64_t generation=con->currentAgentGeneration();
    const passhost_t &host= getBackupHosts()[allindex];
    const std::string commonLabel(host.getICEname());
    const bool side=host.side;
    const std::string hostname(con->rendezvousHost);
    const uint16_t rendezvousPort=con->rendezvousPort;
    CreateAgentData body(commonLabel,side,con->sdp,con->sdplen);
    std::vector<char> doneBody(body.data(),body.data()+body.size());
    std::thread th{[allindex,agent,generation,commonLabel,side,hostname,rendezvousPort,
                    doneBody=std::move(doneBody)] {
        LOGGERICE("Gathering done %s %d\n",commonLabel.data(),side);
        ICEConnect *con=static_cast<ICEConnect*>(connections[allindex]);
        if(!con||!con->isCurrentAgent(agent,generation))
            return;
        std::string_view done{"/done"sv};
        con->startDone.wait(true);
        if(!con->isCurrentAgent(agent,generation))
            return;
        while(true) {
            auto [resbody,code]=ContextHTTPS::getContext().putRequest(
                hostname,rendezvousPort,done,doneBody,{},
                rendezvousRequestOptions(con));
            if(!con->isCurrentAgent(agent,generation))
                return;
            if(code==200) {
                LOGGERICE("%s %d: OK DONE\n",commonLabel.data(),side);
                break;
               }
            if(code==400) {
                LOGGERICE("%s %d: WRONG DONE\n",commonLabel.data(),side);
                break;
                }
            LOGGERICE("%s %d: ERROR DONE code=%d\n",commonLabel.data(),side,code);
            if(!waitForCurrentAgent(con,agent,10))
                return;
              };
          }};
       th.detach();
    }

// Agent 2: on local candidates gathering done

// Agent 1: on message received
#include "ICE_data.hpp"
static void on_recv1(juice_agent_t *agent, const char *data, size_t size, void *user_ptr) {
    const int allindex=(int)(long)user_ptr;
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con)
        return;
    const passhost_t &host= getBackupHosts()[allindex];
    if(!host.ICE) {
            LOGGERICE("ERROR: on_recv1 called on non-ICE host allindex=%d name=%s\n",allindex, host.getICEname());
            return;
            }
    if(size<sizeof(udp_header)) {
            LOGGERICE("ERROR: short ICE packet allindex=%d size=%zu\n",allindex,size);
            return;
            }
    ICE_data *userdata=con->icedata;
    udp_header  *head=const_cast<udp_header *>(reinterpret_cast<const udp_header *>(data));
    userdata[head->side!=host.side].on_recv(agent,data,size,allindex);
    }

static int selectedCloneTransportCode(juice_agent *agent) {
    char localAddr[JUICE_MAX_ADDRESS_STRING_LEN];
    char remoteAddr[JUICE_MAX_ADDRESS_STRING_LEN];
    if (juice_get_selected_addresses_inc_type(
            agent,
            localAddr,
            JUICE_MAX_ADDRESS_STRING_LEN,
            remoteAddr,
            JUICE_MAX_ADDRESS_STRING_LEN) != 0) {
        return clone_transport_unknown;
    }
    return strstr(localAddr, " Relay") || strstr(remoteAddr, " Relay")
        ? clone_transport_turn
        : clone_transport_local_ice;
}

static void wakeCloneSender(const passhost_t &host,uintptr_t reason) {
    if(!backup||host.index<0||
       static_cast<size_t>(host.index)>=backup->con_vars.size())
        return;
    if(auto *sender=backup->con_vars[host.index]) {
        LOGGERICE("wake Clone sender index=%d reason=%lx\n",host.index,
                  static_cast<unsigned long>(reason));
        sender->wakebackup(reason);
        }
}

static bool diagnostics(juice_agent *agent,const char *name,bool side) {
    bool success=true;
    // Retrieve candidates
    char local[JUICE_MAX_CANDIDATE_SDP_STRING_LEN];
    char remote[JUICE_MAX_CANDIDATE_SDP_STRING_LEN];
    if (int  res=juice_get_selected_candidates(agent, local, JUICE_MAX_CANDIDATE_SDP_STRING_LEN, remote,
                                       JUICE_MAX_CANDIDATE_SDP_STRING_LEN);res==0) {
        LOGGERICE("%s %d: Local candidate: %s\n",name,side, local);
        LOGGERICE("%s %d: Remote candidate: %s\n",name,side, remote);
        }
    else {
        success=false;
        }
    // Retrieve addresses
    char localAddr[JUICE_MAX_ADDRESS_STRING_LEN];
    char remoteAddr[JUICE_MAX_ADDRESS_STRING_LEN];
    if (int res=juice_get_selected_addresses_inc_type(agent, localAddr, JUICE_MAX_ADDRESS_STRING_LEN, remoteAddr, JUICE_MAX_ADDRESS_STRING_LEN);res == 0) {
        LOGGERICE("%s %d: Local address: %s\n", name,side,localAddr);
        LOGGERICE("%s %d: Remote address: %s\n", name,side,remoteAddr);
    }
   else {
        LOGGERICE("%s %d: juice_get_selected_addresses failed: %s (%d)\n",name,side,juiceErrorString(res),res);
        success=false;
      }
      return success;
    }

/*
void startICEReceiver(passhost_t *host,ICEConnect *con) {
    LOGGERICE("start startICEReceiver %s\n",host->getname());
    struct FUNC {
        static void connect( passhost_t *host,ICEConnect *con) {
            LOGGERICE("startICEReceiver %s\n",host->getname());
            sleep(5);
            con->connect(host);
            }
        };
    std::thread th(FUNC::connect,host,con);
    th.detach();
    } */
extern void receiverthread(passhost_t *host,const int allindex);
static void on_state_changed1(juice_agent_t *agent, juice_state_t state, void *user_ptr) {
   const int allindex=(int)(long)user_ptr;
    LOGGERICE("on_state_changed1 allindex=%d\n",allindex);
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con)
        return;
    const passhost_t &host= getBackupHosts()[allindex];
    LOGGERICE("%s %d State: %s\n", host.getICEname().data(),host.side,juice_state_to_string(state));
    con->state=state;
    switch(state) {
        case	JUICE_STATE_GATHERING:
        case	JUICE_STATE_CONNECTING:
            con->selectedCloneTransport.store(clone_transport_unknown);
            con->notConnected();
            break;
        case JUICE_STATE_DISCONNECTED:
            con->selectedCloneTransport.store(clone_transport_unknown);
            con->notConnected();
            break;
        case JUICE_STATE_CONNECTED: {
            setConnectTime(allindex,0);
            con->setConnected();
            con->connectTime=time(nullptr);
            con->selectedCloneTransport.store(selectedCloneTransportCode(agent));
            const uint64_t generation=con->currentAgentGeneration();
            struct CONNECTED {
                static void thread(juice_agent_t *agent,int allindex,uint64_t generation) {
                   LOGGERICE("start CONNECT::thread allindex=%d\n",allindex);
                    passhost_t &host= getBackupHosts()[allindex];
                    ICEConnect *con=static_cast<ICEConnect*>(connections[allindex]);
                    if(!con||!con->isCurrentAgent(agent,generation)) {
                        LOGGERICE("connection[%d] no longer owns agent=%p\n",allindex,agent);
                        return;
                        }

                   con->icedata[host.side].sendStart(agent,con);
                   if(!con->isCurrentAgent(agent,generation))
                       return;
                   con->startSending.wait(true);
                   if(!con->isCurrentAgent(agent,generation))
                       return;
                   LOGGERICE("allindex=%d After con->startSending.wait(true)\n",allindex);
                   wakeCloneSender(host,wakeall);
                   {
                   std::lock_guard<std::mutex> lck(con->receiveThreadMutex);
                   con->wakeReceiver=true;
                   con->receiveThreadCon.notify_one();
                   }
                   con->startDone.clear();
                   con->startDone.notify_all();
                   };
                   };
            std::thread th(CONNECTED::thread,agent,allindex,generation);
            th.detach();
            diagnostics(agent,host.getICEname().data(),host.side);
            }; break;
        case JUICE_STATE_FAILED: {
            con->selectedCloneTransport.store(clone_transport_unknown);
            const std::string commonLabel(host.getICEname());
            const bool side=host.side;
            const std::string hostname(con->rendezvousHost);
            const uint16_t rendezvousPort=con->rendezvousPort;
            const bool verifyCertificate=con->verifyRendezvousCertificate;
            CreateAgentData body(commonLabel,side,con->sdp,con->sdplen);
            std::vector<char> failureBody(body.data(),body.data()+body.size());

            // Start replacement negotiation immediately. Reporting the old
            // description to the rendezvous service is useful cleanup, but it
            // must not hold reconnection hostage while the network is absent.
            con->endConnectionHere();
            wakeCloneSender(host,wakeall|wakereconnect);
            {
            std::lock_guard<std::mutex> lck(con->receiveThreadMutex);
            con->wakeReceiver=true;
            con->receiveThreadCon.notify_one();
            }
            std::thread th{[commonLabel,side,hostname,rendezvousPort,verifyCertificate,
                            failureBody=std::move(failureBody)] {
                std::string_view failure{"/failure"sv};
                for(int i=0;i<3;++i) {
                    auto [resbody,code]=ContextHTTPS::getContext().putRequest(
                        hostname,rendezvousPort,failure,
                        std::span(failureBody.data(),failureBody.size()),{},
                        {.verifyCertificate=verifyCertificate});
                    if(code==200) {
                        LOGGERICE("%s %d: OK FAILURE\n",commonLabel.data(),side);
                        return;
                        }
                    LOGGERICE("%s %d: ERROR FAILURE code=%d\n",commonLabel.data(),side,code);
                    sleep(5);
                    }
                }};
            th.detach();
             }
            break;

        default:break;

        };
    LOGARICE("end on_state_changed1");
    }

void ICEConnect::receiverThread(int argindex) {
    destruct runningGuard{[this] { releaseReceiverThread(); }};
    #ifndef HAVE_NOPRCTL
      constexpr const int maxbuf=14;
      char name[14];
      snprintf(name,maxbuf,"ICE Receive %d",argindex);
       prctl(PR_SET_NAME, name, 0, 0, 0);
    #endif
    passhost_t &host= getBackupHosts()[argindex];
    LOGGERICE("start receiverThread argindex=%d\n",argindex);
    int waitsec=host.isSender()?120:1;
    while(true) {
        if(argindex!=allindex) {
            LOGARICE("receiverThread: allindex changed, return");
            return;
            }
         if(!host.ICE) {
            LOGARICE("receiverThread: not ICE, return");
            return;
            }
        if(finish) {
            LOGARICE("Finish receiverThread");
            return;
            }

        LOGGER("receiverThread  before wait_for %d seconds\n",waitsec);
        {
        std::unique_lock<std::mutex> lck(receiveThreadMutex);
        receiveThreadCon.wait_for(lck,std::chrono::seconds(waitsec), [this] {return wakeReceiver.load(); });
        }
        wakeReceiver=false;
        if(host.deactivated) {
            LOGGERICE("allindex=%d receiverThread parked: host deactivated\n",allindex);
            waitsec=5*60;
            continue;
            }
        if(isConnected) {
            notifyReceive();
            if(receiveConnect(&host)) {
                LOGARICE("running receiverthread");
                receiverthread(&host,argindex);
                sleep(1);
//                icedata[1].reStarted();
                waitsec=70;
                }
             else {
                waitsec=60;
                }
             }
         if(!host.isSender()) {
                LOGGERICE("allindex=%d %d receiverThead, make connect\n",allindex,host.side);
                if(finish) {
                    LOGARICE("2: Finish done thread");
                    return;
                    }
                if(argindex!=allindex) {
                    LOGARICE("receiverThread: allindex changed 2, return");
                    return;
                    }
                switch(connect(&host)) {
                    case 1: {
                           LOGGERICE("side=%d receiverThread: connected\n",host.side);
                           waitsec=2*60;
                           continue;
                           };
                    case -2:
                        LOGGERICE("side=%d receiverThread: connect continue old agent\n",host.side);
                        waitsec=4;
                        continue;
                    case 0:
                        LOGGERICE("side=%d receiverThread: already connecting\n",host.side);
                        waitsec=5;
                        continue;
                    case -1:
                        LOGGERICE("side=%d receiverThread: error retry\n",host.side);
                        // Connectivity callbacks can wake this sooner. Keep a
                        // short polling fallback so a returning mobile network
                        // does not leave Clone stale for several minutes.
                        waitsec=5;
                        continue;
                    };
                     break;
             }
         else {
            waitsec=5*60;
            continue;
            }
        waitsec=70;
        }
    };

void startReceiverThread(int allindex) {
    if(ICEConnect *con=static_cast<ICEConnect *>(connections[allindex])) {
        if(!con->claimReceiverThread()) {
            LOGGER("startReceiverThread(%d): already running\n",allindex);
            {
            std::lock_guard<std::mutex> lock(con->receiveThreadMutex);
            con->wakeReceiver=true;
            }
            con->receiveThreadCon.notify_one();
            return;
            }
        LOGGER("startReceiverThread(%d)\n",allindex);
        std::thread th{&ICEConnect::receiverThread,con,allindex};
        th.detach();
        }
    else {
        LOGGER("startReceiverThread() connections[%d]=NULL\n",allindex);
        }
    }

void wakeICEReceiversForNetworkChange(bool resetConnections) {
    if (!backup)
        return;
    const int hostCount = backup->gethostnr();
    for (int allindex = 0; allindex < hostCount; ++allindex) {
        const passhost_t &host = getBackupHosts()[allindex];
        if (!host.ICE || host.deactivated)
            continue;
        ICEConnect *connection = static_cast<ICEConnect *>(connections[allindex]);
        if (!connection)
            continue;
        if (resetConnections)
            connection->endConnectionHere();
        {
            std::lock_guard<std::mutex> lock(connection->receiveThreadMutex);
            connection->wakeReceiver = true;
        }
        connection->receiveThreadCon.notify_one();
    }
}
static  void juice_logger(juice_log_level_t level, const char *message) {
        if(message) {
            LOGGER("libjuice%d: %s\n",level,message);
            }
        else
            LOGGER("libjuice%d: NO MESSAGE!!!\n",level);
        }
//juice_log_level_t juice_log_level=JUICE_LOG_LEVEL_DEBUG;
//juice_log_level_t juice_log_level=JUICE_LOG_LEVEL_VERBOSE;
//   juice_set_log_level(JUICE_LOG_LEVEL_WARN);

class initJuice {
  public:
  initJuice() {
        LOGAR("initJuice()");
         juice_set_log_handler(&juice_logger);
         juice_set_log_level(juice_log_level);
         };
     };

static std::pair<const char *,const char *> getloginpass(char *twiliooutput,const int len) {
     char *endstr=twiliooutput+len;
     char *startsearch=twiliooutput+(len>300?len-300:0);
    std::string_view  password{R"("password": ")"};
    if(auto *pos=std::search(startsearch,endstr,&password[0],password.end());pos!=endstr) {
        char *pass=pos+password.size();
        char *endpass=std::find(pass,endstr,'"');
        if(endpass==endstr) {
                LOGAR(R"(ERROR: password no ending ")");
                return {};
                }
        *endpass++='\0';
        std::string_view  username{R"("username": ")"};
        if(auto *pos=std::search(startsearch,endstr,&username[0],username.end());pos!=endstr) {
            char *user=pos+username.size();
            char *enduser=std::find(user,endstr,'"');
            if(enduser==endstr) {
                    LOGAR(R"(ERROR username no ending ")");
                    return {};
                    }
            *enduser++='\0';
            return {user,pass};
            }
        }
     return {};
    }
#if __has_include("twilio.local.hpp")
#include "twilio.local.hpp"
#define JUGGLUCO_HAS_TWILIO_TOKEN 1
#else
#define JUGGLUCO_HAS_TWILIO_TOKEN 0
#endif
// twilio.local.hpp can define:
// #define TWILIOACCOUNT  // TWILIO_ACCOUNT_SID
// #define USERPASSBASE64 // base64 TWILIO_ACCOUNT_SID:TWILIO_AUTH_TOKEN
extern time_t oldTwilioTimes;
time_t oldTwilioTimes=
#if JUGGLUCO_HAS_TWILIO_TOKEN
0;
#else
std::numeric_limits<time_t>::max();
#endif

static bool turnTextPresent(const char *value) {
    return value&&value[0];
    }

static bool isTwilioTurnServer(const juice_turn_server_t &server) {
    return turnTextPresent(server.host)&&!strcmp(server.host,"global.turn.twilio.com");
    }

static bool hasTurnCredentials(const juice_turn_server_t &server) {
    return server.username&&server.password;
    }

#if JUGGLUCO_HAS_TWILIO_TOKEN
static void refreshTwilioTurnCredentials(juice_turn_server_t *servers,int servercount) {
    bool hasTwilio=false;
    for(int i=0;i<servercount;++i) {
        if(isTwilioTurnServer(servers[i])) {
            hasTwilio=true;
            break;
            }
        }
    if(!hasTwilio) {
        oldTwilioTimes=std::numeric_limits<time_t>::max();
        return;
        }
    static std::mutex mut;
    std::lock_guard<std::mutex> lck(mut);
    auto now=time(nullptr);
    if(now<oldTwilioTimes)
        return;
    const auto url{"https://api.twilio.com/2010-04-01/Accounts/" TWILIOACCOUNT  "/Tokens.json"sv};
    const std::span<const char> input{(const char *)nullptr,0};
    const std::string_view header{"\r\nAuthorization: Basic " USERPASSBASE64 };
    auto [resbody,code]=ContextHTTPS::getContext().postRequest("api.twilio.com",443,url,input,header);
    if(code==201) {
        auto [user,pass]=getloginpass(resbody.data(),resbody.size());
        if(turnTextPresent(user)&&turnTextPresent(pass)) {
            static std::string username;
            static std::string password;
            username=user;
            password=pass;
            for(int i=0;i<servercount;++i) {
                if(isTwilioTurnServer(servers[i])) {
                    servers[i].username=username.data();
                    servers[i].password=password.data();
                    }
                }
            oldTwilioTimes=now+24*60*60;
            LOGAR("TWILIO: refreshed TURN credentials");
            return;
            }
        LOGAR("TWILIO ERROR: missing username/password in token response");
        }
    else {
        LOGGER("TWILIO ERROR: code=%d\n",code);
        }
    oldTwilioTimes=now+15*60;
    }
#else
static void refreshTwilioTurnCredentials(juice_turn_server_t *,int) {
    }
#endif

static std::vector<juice_turn_server_t> usableDefaultTurnServers(juice_turn_server_t *servers,int servercount) {
    std::vector<juice_turn_server_t> usable;
    usable.reserve(servercount);
    for(int i=0;i<servercount;++i) {
        auto &server=servers[i];
        if(!turnTextPresent(server.host)) {
            continue;
            }
        if(!hasTurnCredentials(server)) {
            LOGGER("createAgent: skipping TURN server %s without credentials\n",server.host);
            continue;
            }
        usable.push_back(server);
        }
    return usable;
    }

bool shouldRecreateAgentsForTurnRefresh() {
#if JUGGLUCO_HAS_TWILIO_TOKEN
    auto *data=backup?backup->getupdatedata():nullptr;
    const std::lock_guard<std::mutex> lock(turn_server_mutex);
    if(data&&data->NRturnserver&&data->turnserver[0].hostname[0])
        return false;
    return time(nullptr)>oldTwilioTimes;
#else
    return false;
#endif
    }

juice_agent *createAgent(int allindex) {
    static initJuice el;
    LOGGER("createAgent(%d)\n",allindex);
              
    static   juice_turn_server_t default_turn_servers[]{
#if __has_include("turnservers.local.hpp")
        #include "turnservers.local.hpp"
#else
        #include "turnservers.hpp"
#endif
        /*
        for example:
        {
        .host="relay1.expressturn.com",
        .username="efPU52K4SLOQ34W2QY",
        .password="1TJPNFxHKXrZfelz",
        .port=3480
        } */
         };

    static constexpr const  int defaultservercount=sizeof(default_turn_servers)/sizeof(default_turn_servers[0]);
    const juice_turn_server_t *turn_servers;
    int servercount;

    juice_turn_server_t conf_server;
    std::string configured_turn_host;
    std::string configured_turn_username;
    std::string configured_turn_password;
    uint16_t configured_turn_port=3478;
    bool has_configured_turn=false;
    std::vector<juice_turn_server_t> usable_turn_servers;
    auto *updatedata = backup->getupdatedata();
    {
        const std::lock_guard<std::mutex> lock(turn_server_mutex);
        if(updatedata->NRturnserver&&updatedata->turnserver[0].hostname[0]) {
            configured_turn_host=updatedata->turnserver[0].hostname;
            configured_turn_username=updatedata->turnserver[0].username;
            configured_turn_password=updatedata->turnserver[0].password;
            configured_turn_port=updatedata->turnserver[0].port;
            has_configured_turn=true;
            }
        else if(updatedata->NRturnserver) {
            LOGAR("createAgent: ignoring empty configured TURN host");
            updatedata->NRturnserver=0;
            }
        }
    if(has_configured_turn) {
        conf_server.host=configured_turn_host.c_str();
        conf_server.username=configured_turn_username.c_str();
        conf_server.password=configured_turn_password.c_str();
        conf_server.port=configured_turn_port;
        servercount=1;
        turn_servers=&conf_server;
        }
    else {
        refreshTwilioTurnCredentials(default_turn_servers,defaultservercount);
        usable_turn_servers=usableDefaultTurnServers(default_turn_servers,defaultservercount);
        servercount=static_cast<int>(usable_turn_servers.size());
        turn_servers=servercount?usable_turn_servers.data():nullptr;
        }
    const auto networkConfig=currentICEConfig();
    const bool useConfiguredTurnForStun=
        networkConfig.useTurnForStun&&has_configured_turn;
    if(networkConfig.useTurnForStun&&!has_configured_turn)
        LOGAR("createAgent: TURN-for-STUN requested without a configured TURN server");
    const char *stunHost=useConfiguredTurnForStun
        ?configured_turn_host.c_str():"stun.l.google.com";
    const uint16_t stunPort=useConfiguredTurnForStun
        ?configured_turn_port:19302;
      juice_config_t config1{
            .concurrency_mode=JUICE_CONCURRENCY_MODE_THREAD,
            .stun_server_host = stunHost,
            .stun_server_port = stunPort,
            .turn_servers=(juice_turn_server_t*)  turn_servers,
            .turn_servers_count=servercount,  
            .cb_state_changed = on_state_changed1,
            .cb_candidate = on_candidate1,
            .cb_gathering_done = on_gathering_done1,
            .cb_recv = on_recv1,
            .user_ptr=(void*)(long)allindex
            };
       auto*ret= juice_create(&config1);
       LOGGER("end createAgent(%d)=%p\n",allindex,ret);
       return ret;
      };

extern void   recreateAgents();
void   recreateAgents() {
    // ICE agents are now destroyed on every network reset and reconnect, so
    // there is no reusable agent that needs to be marked for recreation.
    LOGAR("recreateAgents(): agents are recreated on reconnect");
    }
static std::string_view description="/description";

static bool waitonDescription(juice_agent *agent,int allindex,std::string_view commonLabel,int side,
                              std::string_view hostname,uint16_t rendezvousPort) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con)
        return false;
    const uint64_t generation=con->currentAgentGeneration();
    CreateAgentData sdpdata(commonLabel,side,"");
    LOGGERICE("getdescription %s\n",sdpdata.data());
    if(commonLabel.size()<10) { //TODO: becomes 16 later
        LOGGERICE("getdescription: ERROR %s size=%d side=%d\n",commonLabel.data(),commonLabel.size(),side);
        return false;
    }
    while(true) {
        if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
            return false;
        auto [resbody,code]=ContextHTTPS::getContext().getRequest(
            hostname,rendezvousPort,description,sdpdata.getSpan(),{},
            rendezvousRequestOptions(con));
        if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
            return false;
        if(code== 200) {
            if(resbody.size()>= (sizeof(BackDescription )+20)) {
                const BackDescription *other=reinterpret_cast<const BackDescription *>(resbody.data());
                LOGGERICE("getdescription SUCCESS: %s %d: Remote description in:\n%*.s\n",commonLabel.data(),side,resbody.size()-offsetof(BackDescription,description),other->description);
                juice_set_remote_description(agent, other->description);
                return true;
                }
             else {
                LOGGERICE("getdescription failure %s size=%d: getdescription Remote small body in :\n%.*s\n",commonLabel.data(),(int)resbody.size(),(int)resbody.size(),(const char *)resbody.data());
                if(!waitForCurrentAgent(con,agent,1))
                    return false;
                }
            }
         else {
            LOGGERICE("getdescription failure %s %d: %s returns code=%d\n",commonLabel.data(),side,sdpdata.data(),code); 
            if(!waitForCurrentAgent(con,agent,5))
                return false;
            }
        const auto lastfailedtime=getConnectTime(allindex);
        if(lastfailedtime&&(time(nullptr)-lastfailedtime)>maxconnectionunused)
            setConnectTime(allindex,time(nullptr));
        }
//    return false;
    }
static  bool putDescription(int allindex,juice_agent *agent,std::string_view commonLabel,bool side,
                            std::string_view hostname,uint16_t rendezvousPort)  {
    ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
    if(const int error=juice_get_local_description(agent, con->sdp, JUICE_MAX_SDP_STRING_LEN);JUICE_ERR_SUCCESS!=error) {
        LOGGERICE("%s %d: juice_get_local_description failed: %s (%d)\n",commonLabel.data(),side,juiceErrorString(error),error);
        return  false;
        }
     con->sdplen=strlen(con->sdp);
    CreateAgentData sdpdata(commonLabel,side,con->sdp,con->sdplen);
    if(commonLabel.size()<10) { //TODO: becomes 16 later
        LOGGERICE("putdescription: ERROR %s size=%d side=%d\n",commonLabel.data(),commonLabel.size(),side);
        return false;
    }
    const uint64_t generation=con->currentAgentGeneration();
    while(true) {
            if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
                return false;
            LOGGERICE("putdescription: %s %d: Local description:\n%s\n",commonLabel.data(),side, con->sdp);
            auto [resbody,code]=ContextHTTPS::getContext().putRequest(
                hostname,rendezvousPort,description,
                std::span((const char *)sdpdata.data(),sdpdata.size()),{},
                rendezvousRequestOptions(con));
            if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
                return false;
            if(code==200) {
                if(resbody.size()>= (sizeof(BackDescription )+20)) {
                    const BackDescription *other=reinterpret_cast<const BackDescription *>(resbody.data());
                    LOGGERICE("putdescription %s %d: received Remote in:\n%s\n",commonLabel.data(),side,other->description);
                    if(side==givefirst)
                        juice_set_remote_description(agent, other->description);
                    return true;
                    }
                 else {
                    LOGGERICE("putdescription: %s %d: Remote small body in :\n%s\n",commonLabel.data(),side,(const char *)resbody.data());
                    if(!waitForCurrentAgent(con,agent,1))
                        return false;
                    }
                }
             else if(code==400) {
                LOGGERICE("putdescription: %s %d: generation rejected\n",commonLabel.data(),side);
                return false;
                }
             else {
                LOGGERICE("putdescription: %s %d: Http error\n",commonLabel.data(),side);
                if(!waitForCurrentAgent(con,agent,5))
                    return false;
                }

        const auto lastfailedtime=getConnectTime(allindex);
        if(lastfailedtime&&(time(nullptr)-lastfailedtime)>maxconnectionunused)
            setConnectTime(allindex,time(nullptr));
        }
    }


bool initAgent(juice_agent *agent,int allindex) {
    if(allindex>=backup->getupdatedata()->hostnr)
        return false;
    const passhost_t &host= getBackupHosts()[allindex];
    std::string_view commonLabel=host.getICEname();
    ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
    const std::string hostname=con->rendezvousHost;
    const uint16_t rendezvousPort=con->rendezvousPort;
    LOGGER("initAgent %s allindex=%d side=%d\n",commonLabel.data(),allindex,host.side);
    int32_t firstfailed=getConnectTime(allindex);
    int32_t now=time(nullptr);
    if(!firstfailed)
        setConnectTime(allindex,now);
    else  {
        if((now-firstfailed)>maxconnectionunused)
            setConnectTime(allindex,now);
        }
    bool side=host.side;
    startPeerGenerationWatch(con,agent,allindex,commonLabel,side,hostname,
                             rendezvousPort);
    if(side!=givefirst) {
        con->phase=GetDescription;
        if(!waitonDescription(agent,allindex,commonLabel,side,hostname,rendezvousPort)) {
           LOGGERICE("initAgent %s %d: waitonDescription failed\n",commonLabel.data(),side);
            return false;
        }
      if(!stillworking(allindex))
        return false;
      }
    con->phase=PutDescription;
    if(!putDescription(allindex,agent, commonLabel, side,hostname,rendezvousPort)) {
        LOGGERICE("initAgent %s %d: putDescription failed\n",commonLabel.data(),side);
        return false;
         }
    if(!stillworking(allindex))
        return false;

    std::jthread receive{getAddressesThread,agent,allindex,
                         con->currentAgentGeneration(),std::string(commonLabel),side,
                         hostname,rendezvousPort};
    LOGGERICE("initAgent %s %d: Before juice_gather_candidates\n",commonLabel.data(),side);
    con->phase=GatherCandidates;
    int ret=juice_gather_candidates(agent);
    if(con->endConnect.load()||!stillworking(allindex))
        return false;
    LOGGERICE("initAgent %s %d: After juice_gather_candidates(%p)=%d\n",commonLabel.data(),side,agent,ret);
    return ret==JUICE_ERR_SUCCESS;
  }
