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
#include "GenerationWatchRetry.hpp"
#include "RendezvousCandidateStream.hpp"
#include "RecoveryTrace.hpp"
#include "RecoveryWakeBridge.hpp"
#include "net/makerandom.hpp"
#include "BundledTurnServers.hpp"
#include "TurnTokenResponse.hpp"
#include "IceLogPrivacy.hpp"

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
                     bool useTurnForStun, bool verifyRendezvousCertificate,
                     bool useLocalDiscovery, bool preferIPv4,
                     bool allowFallbackServers) {
    const std::lock_guard<std::mutex> lock(ice_config_mutex);
    ice_config.rendezvousHost=std::move(rendezvousHost);
    ice_config.rendezvousPort=rendezvousPort?rendezvousPort:6789;
    ice_config.useTurnForStun=useTurnForStun;
    ice_config.verifyRendezvousCertificate=verifyRendezvousCertificate;
    ice_config.useLocalDiscovery=useLocalDiscovery;
    ice_config.preferIPv4=preferIPv4;
    ice_config.allowFallbackServers=allowFallbackServers;
    }

std::vector<RendezvousEndpoint> resolveRendezvousEndpoints(std::string_view label) {
    const auto config=currentICEConfig();
    // Without a custom server the configured port still applies to the
    // public one, as before failover existed.
    const uint16_t publicPort=config.rendezvousHost.empty()?config.rendezvousPort:6789;
    // A custom server is used alone unless the user allows the public ones
    // as fallback.
    const bool usePublic=config.rendezvousHost.empty()||config.allowFallbackServers;
    return buildRendezvousEndpoints(
        {config.rendezvousHost,config.rendezvousPort,
         config.verifyRendezvousCertificate},
        usePublic?std::span<const std::string_view>(hostnames):std::span<const std::string_view>(),
        static_cast<size_t>(hostselect(label)),publicPort);
    }

ICEConnect::ICEConnect(int allindex,const passhost_t &host)
    :Connect(allindex),side(host.side) {
    reloadNetworkConfig(host);
    agent.store(nullptr);
    }

void ICEConnect::reloadNetworkConfig(const passhost_t &host) {
    rendezvousEndpoints=resolveRendezvousEndpoints(host.getICEname());
    const auto &primary=rendezvousEndpoints.front();
    rendezvousHost=primary.host;
    rendezvousPort=primary.port;
    verifyRendezvousCertificate=primary.verifyCertificate;
    setActiveRendezvous(0);
    useLocalDiscovery=currentICEConfig().useLocalDiscovery;
    }
#ifndef LOGGER
#define LOGGER(...) fprintf(stderr,__VA_ARGS__)
#endif
#define BUFFER_SIZE 4096

#define JUICE_ERR_SUCCESS 0

const char *juiceErrorString(int error);
static bool restartRejectedNegotiation(ICEConnect *con,juice_agent_t *agent,
                                       uint64_t generation,int allindex,
                                       const char *reason,bool protectRecovery=false);

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

static bool applyRemoteDescription(int allindex, juice_agent_t *agent,
                                   uint64_t generation,
                                   std::string_view description,
                                   bool fromLocalNetwork,
                                   std::string_view remoteGeneration={}) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con||!con->isCurrentAgent(agent,generation)||description.empty())
        return false;
    bool expected=false;
    if(!con->remoteDescriptionSet.compare_exchange_strong(expected,true))
        return false;
    // Publish the winning signaling channel before handing the description to
    // libjuice. The rendezvous generation watcher runs concurrently and must
    // be able to recognize the authenticated LAN generation immediately.
    con->remoteDescriptionWasLocal=fromLocalNetwork;
    if(fromLocalNetwork)
        con->setAcceptedLocalPeerGeneration(remoteGeneration);
    const std::string terminated(description);
    const int result=juice_set_remote_description(agent,terminated.c_str());
    if(result!=JUICE_ERR_SUCCESS) {
        if(fromLocalNetwork)
            con->clearAcceptedLocalPeerGeneration();
        con->remoteDescriptionWasLocal=false;
        con->remoteDescriptionSet=false;
        LOGGERICE("remote description rejected: %s (%d)\n",
                  juiceErrorString(result),result);
        return false;
        }
    con->turnRelayRotation.answered(generation);
    if(auto local=con->currentLocalSignal())
        local->setAcceptedRemoteDescription(description);
    if(fromLocalNetwork) {
        con->cancelRendezvous();
        }
    return true;
    }

bool applyLocalICEDescription(int allindex, juice_agent_t *agent,
                              uint64_t agentGeneration,
                              std::string_view description,
                              std::string_view remoteGeneration) {
    return applyRemoteDescription(allindex,agent,agentGeneration,description,
                                  true,remoteGeneration);
    }

void applyLocalICECandidate(int allindex, juice_agent_t *agent,
                            uint64_t agentGeneration,
                            std::string_view candidate) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con||!con->isCurrentAgent(agent,agentGeneration)||candidate.empty())
        return;
    const std::string terminated(candidate);
    juice_add_remote_candidate(agent,terminated.c_str());
    }

void applyLocalICEGatheringDone(int allindex, juice_agent_t *agent,
                                uint64_t agentGeneration) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(con&&con->isCurrentAgent(agent,agentGeneration))
        juice_set_remote_gathering_done(agent);
    }

void localICEPeerGenerationChanged(int allindex, juice_agent_t *agent,
                                   uint64_t agentGeneration) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(con)
        restartRejectedNegotiation(con,agent,agentGeneration,allindex,
                                   "local peer generation changed",true);
    }

void localICEPromotionAvailable(int allindex, juice_agent_t *agent,
                                uint64_t agentGeneration) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con||!con->isCurrentAgent(agent,agentGeneration)||
       con->cloneTransportCode()!=clone_transport_turn)
        return;
    const passhost_t &host=getBackupHosts()[allindex];
    if(host.side!=givefirst)
        return;
    restartRejectedNegotiation(con,agent,agentGeneration,allindex,
                               "authenticated LAN promotion available");
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

static HTTPSRequestOptions rendezvousRequestOptions(
        ICEConnect *con,const RendezvousEndpoint &endpoint,
        const RendezvousAttempt *attempt=nullptr) {
    return {
        .timeoutMilliseconds=10000,
        .cancelled=con->currentRendezvousCancellation(),
        .superseded=attempt?attempt->superseded:nullptr,
        .verifyCertificate=endpoint.verifyCertificate
        };
    }

// jugglucoconnect holds GET /description (≤6 min), PUT /description (≤11 min),
// GET /address and PUT /done (≤4 min) until the peer acts. Abandoning such a
// request leaves its handler waiting; when it notices the closed socket it
// erases the label's whole entry, and a re-PUT of the description erases it
// too. Connecting stays bounded; the answer is awaited until cancellation.
static HTTPSRequestOptions rendezvousLongPollOptions(
        ICEConnect *con,const RendezvousEndpoint &endpoint,
        const RendezvousAttempt *attempt=nullptr) {
    auto options=rendezvousRequestOptions(con,endpoint,attempt);
    options.responseTimeoutMilliseconds=12*60*1000;
    return options;
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
                                       const char *reason,bool protectRecovery) {
    if(!con->requestReconnectIfCurrent(agent,generation,protectRecovery))
        return false;
    traceIceRecovery(allindex,generation,"restart-requested");
    const passhost_t &host=getBackupHosts()[allindex];
    LOGGERICE("%s %d: restart rejected negotiation: %s\n",
              host.getICEname().data(),host.side,reason);
    wakeCloneSender(host,wakeall|wakereconnect);
    {
    std::lock_guard<std::mutex> lock(con->receiveThreadMutex);
    con->wakeReceiver=true;
    }
    con->receiveThreadCon.notify_one();
    traceIceRecovery(allindex,generation,"restart-notified");
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

enum class PeerGenerationUpdate {
    Observe,
    Same,
    AlignWithAuthenticatedLocal,
    Changed,
};

static constexpr PeerGenerationUpdate classifyPeerGenerationUpdate(
        std::string_view observedPeer,
        std::string_view acceptedLocalPeer,
        std::string_view reportedPeer) {
    if(!observedPeer.empty()&&reportedPeer==observedPeer)
        return PeerGenerationUpdate::Same;
    if(!acceptedLocalPeer.empty()&&reportedPeer==acceptedLocalPeer)
        return PeerGenerationUpdate::AlignWithAuthenticatedLocal;
    if(observedPeer.empty())
        return PeerGenerationUpdate::Observe;
    return PeerGenerationUpdate::Changed;
}

static_assert(classifyPeerGenerationUpdate({}, {}, "a") ==
              PeerGenerationUpdate::Observe);
static_assert(classifyPeerGenerationUpdate("old", "new", "new") ==
              PeerGenerationUpdate::AlignWithAuthenticatedLocal);
static_assert(classifyPeerGenerationUpdate("old", "new", "other") ==
              PeerGenerationUpdate::Changed);

static void watchPeerGeneration(
        juice_agent *agent,int allindex,uint64_t agentGeneration,
        std::string commonLabel,bool side,std::string hostname,
        uint16_t rendezvousPort,std::string localGeneration,
        std::shared_ptr<const std::atomic_bool> cancellation) {
    static constexpr std::string_view path="/generation";
    std::string observedPeer;
    const auto active=[&] {
        ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
        return con&&con->isCurrentAgent(agent,agentGeneration)&&
           !con->endConnect.load()&&
           !(cancellation&&cancellation->load(std::memory_order_acquire));
    };
    runGenerationWatchRequests(active,[&] {
        ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
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
            return GenerationWatchResult::Stop;
        if(code==400) {
            con->generationWatchCapability.store(-1,std::memory_order_release);
            LOGARICE("peer generation watch unsupported");
            return GenerationWatchResult::Stop;
        }
        if(code!=200) {
            LOGGERICE("peer generation watch request failed code=%d; retrying\n",code);
            return GenerationWatchResult::Retry;
        }
        con->generationWatchCapability.store(1,std::memory_order_release);
        const auto peer=generationResponseToken(body);
        if(!peer)
            return GenerationWatchResult::Retry;
        const std::string acceptedLocalPeer=
            con->currentAcceptedLocalPeerGeneration();
        // While a LAN description is being applied, its authenticated peer
        // generation is not yet visible. Ignore this response and let the
        // next long-poll reconcile against the copied generation token.
        if(con->remoteDescriptionWasLocal.load()&&acceptedLocalPeer.empty())
            return GenerationWatchResult::Continue;
        switch(classifyPeerGenerationUpdate(observedPeer,acceptedLocalPeer,
                                             *peer)) {
            case PeerGenerationUpdate::Observe:
                observedPeer=*peer;
                break;
            case PeerGenerationUpdate::Same:
                break;
            case PeerGenerationUpdate::AlignWithAuthenticatedLocal:
                observedPeer=*peer;
                LOGARICE("peer generation watch aligned with authenticated LAN generation");
                break;
            case PeerGenerationUpdate::Changed:
                LOGARICE("peer generation changed");
                restartRejectedNegotiation(con,agent,agentGeneration,allindex,
                                           "peer generation changed",true);
                return GenerationWatchResult::Stop;
        }
        return GenerationWatchResult::Continue;
    },[&](int seconds) {
        const int64_t deadline=elapsedRealtimeMilliseconds()+seconds*1000;
        while(active()) {
            const int64_t remaining=deadline-elapsedRealtimeMilliseconds();
            if(remaining<=0) return true;
            std::this_thread::sleep_for(
                std::chrono::milliseconds(std::min<int64_t>(remaining,250)));
        }
        return false;
    });
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
                               std::string commonLabel,bool side,
                               RendezvousEndpoint endpoint) {
   static std::string_view address{"/address"};
   CreateAgentData addressdata(commonLabel,side,"") ;
   ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
   if(!con||!con->isCurrentAgent(agent,generation)) return;
   const auto cancellation=con->currentRendezvousCancellation();
   const auto active=[&] {
       return connections[allindex]==con&&con->isCurrentAgent(agent,generation)&&!con->endConnect.load()&&
           !con->remoteDescriptionWasLocal.load()&&
           !(cancellation&&cancellation->load(std::memory_order_acquire));
   };
   const auto result=readRendezvousCandidates(active,
       [&] { return con->isConnected.load(); },
       [&] {
           LOGGERICE("getaddress host=%d gen=%llu\n",allindex,
                     static_cast<unsigned long long>(generation));
           auto response=ContextHTTPS::getContext().getRequest(
               endpoint.host,endpoint.port,address,addressdata.getSpan(),{},
               rendezvousLongPollOptions(con,endpoint));
           LOGGERICE("getaddress response host=%d code=%d bytes=%zu\n",
                     allindex,response.second,response.first.size());
           return response;
       },[&](std::string_view candidate) {
           const std::string terminated(candidate);
           const int res=juice_add_remote_candidate(agent,terminated.c_str());
           LOGGERICE("getaddress host=%d candidate res=%d\n",allindex,res);
           return res==JUICE_ERR_SUCCESS||res==JUICE_ERR_IGNORED;
       },[&](int seconds) {
           const auto deadline=elapsedRealtimeMilliseconds()+seconds*1000;
           while(active()) {
               const auto remaining=deadline-elapsedRealtimeMilliseconds();
               if(remaining<=0) return true;
               std::this_thread::sleep_for(
                   std::chrono::milliseconds(std::min<int64_t>(remaining,250)));
           }
           return false;
       },[&](size_t bytes) {
           LOGGERICE("getaddress host=%d ambiguous reply bytes=%zu; still negotiating\n",allindex,bytes);
           traceIceRecovery(allindex,generation,"candidate-stream-pending");
       });
   if(!active()) return;
   if(result==CandidateStreamResult::ConnectedEnd&&con->isConnected.load()) {
       // Legacy completion is ambiguous, but this agent has independently
       // proved connectivity. Never use an empty reply to finish a pending ICE
       // checklist or close its candidate reader before late candidates arrive.
       juice_set_remote_gathering_done(agent);
       LOGGERICE("getaddress host=%d: connected stream ended\n",allindex);
   } else if(result==CandidateStreamResult::Unavailable&&!con->isConnected.load()) {
       restartRejectedNegotiation(con,agent,generation,allindex,
                                  "remote candidate stream unavailable");
   }
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
   const auto endpoint=con->activeRendezvousEndpoint();
   // Keep one signaling transport authoritative for the whole ICE
   // generation. Mixing a rendezvous description with LAN candidates leaves
   // the rendezvous /address reader waiting for completion even after ICE is
   // already connected, which in turn delays the Clone receiver handshake.
   if(con->remoteDescriptionWasLocal.load()) {
       if(auto local=con->currentLocalSignal())
           local->publishCandidate(sdp);
       return;
       }
   static std::string_view address{"/address"};
   CreateAgentData sdpdata(commonLabel,side,sdp) ;
   for(int i=0;i<20;++i) {
       if(!con->isCurrentAgent(agent,generation))
           return;
       auto [resbody,code]=ContextHTTPS::getContext().putRequest(
           endpoint.host,endpoint.port,address,
           std::span((const char *)sdpdata.data(),sdpdata.size()),{},
           rendezvousRequestOptions(con,endpoint));
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
    const auto endpoint=con->activeRendezvousEndpoint();
    // Candidate completion must use the same signaling transport as the
    // description. Otherwise a rendezvous reader can remain blocked while a
    // LAN-only completion message is sent to the peer.
    if(con->remoteDescriptionWasLocal.load()) {
        if(auto local=con->currentLocalSignal())
            local->publishGatheringDone();
        return;
        }
    CreateAgentData body(commonLabel,side,con->sdp,con->sdplen);
    std::vector<char> doneBody(body.data(),body.data()+body.size());
    std::thread th{[allindex,agent,generation,commonLabel,side,endpoint,
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
                endpoint.host,endpoint.port,done,doneBody,{},
                rendezvousLongPollOptions(con,endpoint));
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

static void refreshSelectedCloneTransport(ICEConnect *connection,
                                          juice_agent *agent) {
    const int transport=selectedCloneTransportCode(agent);
    if(transport!=clone_transport_unknown)
        connection->selectedCloneTransport.store(transport);
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
            con->rendezvousExchangedUnconnected=false;
            con->turnRelayRotation.connected(con->currentAgentGeneration());
            if(!con->remoteDescriptionWasLocal.load()&&
               con->activeRendezvousIndex.load(std::memory_order_acquire)==0&&
               con->rendezvousEscalated.exchange(false))
                LOGGERICE("allindex=%d rendezvous: primary server recovered\n",allindex);
            con->connectTime=time(nullptr);
            refreshSelectedCloneTransport(con,agent);
            if(auto local=con->currentLocalSignal())
                local->markConnected();
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
        case JUICE_STATE_COMPLETED:
            // libjuice reaches COMPLETED immediately after CONNECTED once the
            // nominated pair settles. The selected addresses can become
            // queryable between those callbacks, so refresh the route from
            // the final authoritative state as well.
            refreshSelectedCloneTransport(con,agent);
            break;
        case JUICE_STATE_FAILED: {
            con->turnRelayRotation.failedAttempt(con->currentAgentGeneration());
            con->selectedCloneTransport.store(clone_transport_unknown);
            const std::string commonLabel(host.getICEname());
            const bool side=host.side;
            const auto endpoint=con->activeRendezvousEndpoint();
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
            std::thread th{[commonLabel,side,endpoint,
                            failureBody=std::move(failureBody)] {
                std::string_view failure{"/failure"sv};
                for(int i=0;i<3;++i) {
                    auto [resbody,code]=ContextHTTPS::getContext().putRequest(
                        endpoint.host,endpoint.port,failure,
                        std::span(failureBody.data(),failureBody.size()),{},
                        {.verifyCertificate=endpoint.verifyCertificate});
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
    destruct runningGuard{[this] { recoveryWake.cancel(); releaseReceiverThread(); }};
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
        traceIceRecovery(argindex,currentAgentGeneration(),"receiver-wait-begin");
        {
        std::unique_lock<std::mutex> lck(receiveThreadMutex);
        receiveThreadCon.wait_for(lck,std::chrono::seconds(waitsec), [this] {return wakeReceiver.load(); });
        }
        wakeReceiver=false;
        traceIceRecovery(argindex,currentAgentGeneration(),"receiver-wait-end");
        if(host.deactivated) {
            recoveryWake.cancel();
            LOGGERICE("allindex=%d receiverThread parked: host deactivated\n",allindex);
            waitsec=5*60;
            continue;
            }
        if(isConnected) {
            notifyReceive();
            if(receiveConnect(&host)) {
                LOGARICE("running receiverthread");
                receiverthread(&host,argindex);
                traceIceRecovery(argindex,currentAgentGeneration(),"receiver-commands-returned");
                traceIceRecovery(argindex,currentAgentGeneration(),"receiver-retry-pause-begin");
                sleep(1);
                traceIceRecovery(argindex,currentAgentGeneration(),"receiver-retry-pause-end");
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
                traceIceRecovery(argindex,currentAgentGeneration(),"receiver-connect-begin");
                const int connectedResult=connect(&host);
                traceIceRecovery(argindex,currentAgentGeneration(),"receiver-connect-end");
                switch(connectedResult) {
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

void wakeICEReceiversForNetworkChange(bool resetConnections,
                                      bool probeLocalFirst) {
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
        bool probing = false;
        juice_agent_t *agent = connection->agent.load();
        const uint64_t agentGeneration = connection->currentAgentGeneration();
        if (resetConnections && probeLocalFirst && agent &&
            connection->useLocalDiscovery &&
            connection->cloneTransportCode() == clone_transport_turn) {
            if (auto local = connection->currentLocalSignal();
                local && local->requestPromotionProbe()) {
                probing = true;
                LOGGERICE("allindex=%d: probe LAN before replacing TURN generation\n",
                          allindex);
                std::thread([agent, allindex, agentGeneration] {
                    ICEConnect *current = static_cast<ICEConnect *>(
                        connections[allindex]);
                    if (!current ||
                        !waitForCurrentAgent(current, agent, 2) ||
                        current->cloneTransportCode() != clone_transport_turn)
                        return;
                    restartRejectedNegotiation(
                        current, agent, agentGeneration, allindex,
                        "LAN promotion probe timed out after network handover");
                }).detach();
            }
        }
        if (resetConnections && !probing) {
            LOGGERICE("allindex=%d: rebuild ICE generation after network handover\n",
                      allindex);
            connection->endConnectionHere();
        }
        {
            std::lock_guard<std::mutex> lock(connection->receiveThreadMutex);
            connection->wakeReceiver = true;
        }
        connection->receiveThreadCon.notify_one();
    }
}
static  void juice_logger(juice_log_level_t level, const char *message) {
        if(message&&iceLogContainsCredentials(message)) {
            LOGGER("libjuice%d: credential details omitted\n",level);
            return;
            }
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

#if defined(JUGGLUCO_DISTRIBUTION) && __has_include("distribution-twilio.hpp")
#include "distribution-twilio.hpp"
#define JUGGLUCO_HAS_TWILIO_TOKEN 1
#elif !defined(JUGGLUCO_DISTRIBUTION) && __has_include("twilio.local.hpp")
#include "twilio.local.hpp"
#define JUGGLUCO_HAS_TWILIO_TOKEN 1
#else
#define JUGGLUCO_HAS_TWILIO_TOKEN 0
#endif

static std::vector<TurnRelay> twilioTurnRelays() {
#if JUGGLUCO_HAS_TWILIO_TOKEN
    // Token issuance is optional and bounded. A failed / expired account must
    // never remove the independently provisioned static relays from the pool.
    // Construct the shared TLS context before the worker owner, so shutdown
    // joins a pending request before destroying the context it uses.
    auto &context=ContextHTTPS::getContext();
    struct Cache {
        std::mutex mutex;
        std::optional<TurnToken> token;
        int64_t expires=0,refresh=0,nextTry=0;
        bool fetching=false;
        std::jthread request;
    };
    static Cache cache;
    std::lock_guard lock(cache.mutex);
    const auto now=elapsedRealtimeMilliseconds();
    if(now>=cache.refresh&&now>=cache.nextTry&&!cache.fetching) {
        cache.fetching=true;
        cache.nextTry=now+60000;
        cache.request=std::jthread([&context,now] {
            constexpr std::string_view path="/2010-04-01/Accounts/" TWILIOACCOUNT "/Tokens.json";
            constexpr std::string_view authorization="\r\nAuthorization: Basic " USERPASSBASE64
                                                    "\r\nContent-Type: application/x-www-form-urlencoded";
            constexpr std::string_view input="Ttl=86400";
            auto [body,code]=context.postRequest(
                "api.twilio.com",443,path,{input.data(),input.size()},authorization,
                {.timeoutMilliseconds=4000});
            std::lock_guard finished(cache.mutex);
            if(code==201) {
                auto replacement=TurnTokenReader({body.data(),body.size()}).read();
                if(replacement) {
                    cache.token=std::move(replacement);
                    cache.expires=now+static_cast<int64_t>(cache.token->ttlSeconds)*1000;
                    cache.refresh=cache.expires-std::min<int64_t>(3600000,cache.token->ttlSeconds*1000/4);
                } else LOGARICE("Twilio token response invalid; keeping independent static relays");
            } else {
                LOGGERICE("Twilio token request unavailable HTTP=%d; keeping independent static relays\n",code);
            }
            cache.fetching=false;
        });
    }
    if(!cache.token||now>=cache.expires) return {};
    const auto &token=cache.token;
    std::vector<TurnRelay> relays{{"global.turn.twilio.com",token->username,token->password,3478,
             TurnRelayTransport::Udp,"twilio","global"},
            {"global.turn.twilio.com",token->username,token->password,443,
             TurnRelayTransport::Tls,"twilio","global"},
            {"global.turn.twilio.com",token->username,token->password,3478,
             TurnRelayTransport::Tcp,"twilio","global"}};
    // Global uses latency-based routing. Explicit edges provide independent
    // routes when that selected edge / route is unavailable. These region
    // names and TLS port are documented by Twilio's NTS API / regions pages.
    for(const char *region:{"frankfurt","dublin","ashburn","umatilla","singapore",
                            "tokyo","mumbai","sydney","sao-paulo"})
        relays.push_back({std::string(region)+".turn.twilio.com",token->username,
                          token->password,443,TurnRelayTransport::Tls,"twilio",region});
    return relays;
#else
    return {};
#endif
}

juice_agent *createAgent(int allindex) {
    static initJuice el;
    LOGGER("createAgent(%d)\n",allindex);
              
    static constexpr auto &default_turn_servers=bundledTurnServers;
    static constexpr int defaultservercount=std::size(bundledTurnServers);
    const juice_turn_server_t *turn_servers;
    int servercount;

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
    const auto networkConfig=currentICEConfig();
    // A custom relay is used alone unless the user allows the app's relays as
    // fallback; then it stays in every selection and the backups rotate.
    const bool useBundled=!has_configured_turn||networkConfig.allowFallbackServers;
    std::vector<TurnRelay> pool;
    if(has_configured_turn)
        appendTurnRelay(pool,{configured_turn_host,configured_turn_username,
                            configured_turn_password,configured_turn_port,
                            TurnRelayTransport::Udp,"custom",""},true);
    if(useBundled) {
        for(int i=0;i<defaultservercount;++i) {
            const auto &server=default_turn_servers[i];
            const auto &options=bundledTurnOptions[i];
            if(!turnTextPresent(server.host)||!hasTurnCredentials(server)) continue;
            appendTurnRelay(pool,{server.host,server.username,server.password,server.port,
                                options.transport,options.provider,options.region});
            }
        for(auto &relay:twilioTurnRelays()) appendTurnRelay(pool,std::move(relay));
        }
    auto *connection=static_cast<ICEConnect *>(connections[allindex]);
    auto selected=connection->turnRelayRotation.select(pool,connection->currentAgentGeneration(),
                                                       has_configured_turn);
    for(const auto &relay:selected) {
        LOGGERICE("TURN choice %s:%u transport=%d provider=%s region=%s\n",
                  relay.host.c_str(),relay.port,static_cast<int>(relay.transport),
                  relay.provider.c_str(),relay.region.c_str());
        juice_turn_server_t server{relay.host.c_str(),relay.username.c_str(),
                                   relay.password.c_str(),relay.port};
        if(relay.transport!=TurnRelayTransport::Udp) {
            auto stream=TurnStreamRelay::start(relay.host,relay.port,
                                              relay.transport==TurnRelayTransport::Tls);
            if(!stream) continue;
            server.host="127.0.0.1";
            server.port=stream->loopbackPort();
            connection->turnStreams.push_back(std::move(stream));
            }
        usable_turn_servers.push_back(server);
        }
    servercount=static_cast<int>(usable_turn_servers.size());
    turn_servers=servercount?usable_turn_servers.data():nullptr;
    LOGGER("createAgent: %d active TURN relays from %zu configured and bundled choices\n",
           servercount,pool.size());
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
            .user_ptr=(void*)(long)allindex,
            .prefer_ipv4=networkConfig.preferIPv4
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

static constexpr bool shouldPublishDescriptionLocally(
        bool side,bool acceptedOfferLocally) {
    return side==givefirst||acceptedOfferLocally;
    }

static_assert(shouldPublishDescriptionLocally(givefirst,false));
static_assert(shouldPublishDescriptionLocally(!givefirst,true));
static_assert(!shouldPublishDescriptionLocally(!givefirst,false));

// Waits like waitForCurrentAgent(), but also stops when another server has
// already won this negotiation.
static bool waitForAttempt(ICEConnect *con,juice_agent_t *agent,
                           const RendezvousAttempt &attempt,int seconds) {
    const int64_t deadline=elapsedRealtimeMilliseconds()+
                           static_cast<int64_t>(std::max(seconds,0))*1000;
    while(con->isCurrentAgent(agent)&&!con->endConnect.load()&&
          !attempt.isSuperseded()) {
        const int64_t remaining=deadline-elapsedRealtimeMilliseconds();
        if(remaining<=0)
            break;
        std::this_thread::sleep_for(
            std::chrono::milliseconds(std::min<int64_t>(remaining,250)));
        }
    return con->isCurrentAgent(agent)&&!con->endConnect.load()&&
           !attempt.isSuperseded();
    }

static RendezvousAttemptResult stoppedAttempt(const RendezvousAttempt &attempt) {
    return attempt.isSuperseded()?RendezvousAttemptResult::Lost
                                 :RendezvousAttemptResult::Failed;
    }

// Side 1: fetch the offer from one server. Won means it was applied from here.
static RendezvousAttemptResult waitonDescription(
        juice_agent *agent,int allindex,std::string_view commonLabel,int side,
        const RendezvousEndpoint &endpoint,RendezvousAttempt &attempt) {
    ICEConnect *con=currentICEConnection(allindex,agent);
    if(!con)
        return RendezvousAttemptResult::Failed;
    const uint64_t generation=con->currentAgentGeneration();
    CreateAgentData sdpdata(commonLabel,side,"");
    LOGGERICE("getdescription %s via %s\n",sdpdata.data(),endpoint.host.c_str());
    if(commonLabel.size()<10) { //TODO: becomes 16 later
        LOGGERICE("getdescription: ERROR %s size=%d side=%d\n",commonLabel.data(),commonLabel.size(),side);
        return RendezvousAttemptResult::Failed;
    }
    while(true) {
        if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
            return RendezvousAttemptResult::Failed;
        if(con->remoteDescriptionWasLocal.load()) {
            con->beginRendezvous();
            return RendezvousAttemptResult::Lost;
            }
        if(attempt.isSuperseded()||con->remoteDescriptionSet.load())
            return RendezvousAttemptResult::Lost;
        auto [resbody,code]=ContextHTTPS::getContext().getRequest(
            endpoint.host,endpoint.port,description,sdpdata.getSpan(),{},
            rendezvousLongPollOptions(con,endpoint,&attempt));
        if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
            return RendezvousAttemptResult::Failed;
        if(con->remoteDescriptionWasLocal.load()) {
            con->beginRendezvous();
            return RendezvousAttemptResult::Lost;
            }
        if(attempt.isSuperseded())
            return RendezvousAttemptResult::Lost;
        if(code== 200) {
            if(resbody.size()>= (sizeof(BackDescription )+20)) {
                const BackDescription *other=reinterpret_cast<const BackDescription *>(resbody.data());
                LOGGERICE("getdescription SUCCESS: %s %d: Remote description in:\n%*.s\n",commonLabel.data(),side,resbody.size()-offsetof(BackDescription,description),other->description);
                if(applyRemoteDescription(allindex,agent,generation,
                                          other->description,false))
                    return RendezvousAttemptResult::Won;
                if(con->remoteDescriptionSet.load())
                    return RendezvousAttemptResult::Lost;
                }
             else {
                LOGGERICE("getdescription failure %s size=%d: getdescription Remote small body in :\n%.*s\n",commonLabel.data(),(int)resbody.size(),(int)resbody.size(),(const char *)resbody.data());
                if(!waitForAttempt(con,agent,attempt,1))
                    return stoppedAttempt(attempt);
                }
            }
         else {
            if(code<0)
                attempt.connectFailures.fetch_add(1,std::memory_order_release);
            LOGGERICE("getdescription failure %s %d: %s via %s returns code=%d\n",commonLabel.data(),side,sdpdata.data(),endpoint.host.c_str(),code);
            if(!waitForAttempt(con,agent,attempt,5))
                return stoppedAttempt(attempt);
            }
        const auto lastfailedtime=getConnectTime(allindex);
        if(lastfailedtime&&(time(nullptr)-lastfailedtime)>maxconnectionunused)
            setConnectTime(allindex,time(nullptr));
        }
    }

// Reads our description once per agent. libjuice makes the agent that asks
// first controlling, so side 1 must only do this after applying the offer.
static bool prepareLocalDescription(ICEConnect *con,juice_agent *agent,
                                    std::string_view commonLabel,bool side) {
    if(const int error=juice_get_local_description(agent, con->sdp, JUICE_MAX_SDP_STRING_LEN);JUICE_ERR_SUCCESS!=error) {
        LOGGERICE("%s %d: juice_get_local_description failed: %s (%d)\n",commonLabel.data(),side,juiceErrorString(error),error);
        return  false;
        }
     con->sdplen=strlen(con->sdp);
    // Side 0 offers on both signaling transports. Side 1 must answer only on
    // the transport that delivered the accepted offer, otherwise each peer
    // can select a different signaling path for the same ICE generation.
    if(shouldPublishDescriptionLocally(
           side,con->remoteDescriptionWasLocal.load())) {
        if(auto local=con->currentLocalSignal())
            local->publishDescription({con->sdp,static_cast<size_t>(con->sdplen)});
        }
    return true;
    }

// Side 0: offer on one server; Won when its answer was applied from here.
// Side 1: answer on the server that delivered the offer.
static RendezvousAttemptResult putDescription(
        int allindex,juice_agent *agent,std::string_view commonLabel,bool side,
        const RendezvousEndpoint &endpoint,RendezvousAttempt &attempt)  {
    ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
    CreateAgentData sdpdata(commonLabel,side,con->sdp,con->sdplen);
    if(commonLabel.size()<10) { //TODO: becomes 16 later
        LOGGERICE("putdescription: ERROR %s size=%d side=%d\n",commonLabel.data(),commonLabel.size(),side);
        return RendezvousAttemptResult::Failed;
    }
    const uint64_t generation=con->currentAgentGeneration();
    // Side 1 has already applied this server's offer, so no other server can
    // take over the exchange. If the answer cannot be delivered here, restart
    // the negotiation (keeping every server in play) instead of retrying.
    constexpr int maxAnswerTries=3;
    int answerFailures=0;
    const auto answerUndeliverable=[&](bool rejected=false) {
        if(side==givefirst||(!rejected&&++answerFailures<maxAnswerTries))
            return false;
        con->rendezvousEscalated=true;
        restartRejectedNegotiation(con,agent,generation,allindex,
                                   "answer could not be delivered");
        return true;
    };
    while(true) {
            if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
                return RendezvousAttemptResult::Failed;
            if(con->remoteDescriptionWasLocal.load()) {
                con->beginRendezvous();
                return RendezvousAttemptResult::Lost;
                }
            if(attempt.isSuperseded())
                return RendezvousAttemptResult::Lost;
            LOGGERICE("putdescription: %s %d via %s: Local description:\n%s\n",commonLabel.data(),side,endpoint.host.c_str(), con->sdp);
            auto [resbody,code]=ContextHTTPS::getContext().putRequest(
                endpoint.host,endpoint.port,description,
                std::span((const char *)sdpdata.data(),sdpdata.size()),{},
                rendezvousLongPollOptions(con,endpoint,&attempt));
            if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
                return RendezvousAttemptResult::Failed;
            if(con->remoteDescriptionWasLocal.load()) {
                con->beginRendezvous();
                return RendezvousAttemptResult::Lost;
                }
            if(attempt.isSuperseded())
                return RendezvousAttemptResult::Lost;
            if(code==200) {
                if(resbody.size()>= (sizeof(BackDescription )+20)) {
                    const BackDescription *other=reinterpret_cast<const BackDescription *>(resbody.data());
                    LOGGERICE("putdescription %s %d: received Remote in:\n%s\n",commonLabel.data(),side,other->description);
                    if(side!=givefirst||
                       applyRemoteDescription(allindex,agent,generation,
                                              other->description,false))
                        return RendezvousAttemptResult::Won;
                    if(con->remoteDescriptionSet.load())
                        return RendezvousAttemptResult::Lost;
                    }
                 else {
                    LOGGERICE("putdescription: %s %d: Remote small body in :\n%s\n",commonLabel.data(),side,(const char *)resbody.data());
                    if(answerUndeliverable())
                        return RendezvousAttemptResult::Failed;
                    if(!waitForAttempt(con,agent,attempt,1))
                        return stoppedAttempt(attempt);
                    }
                }
             else if(code==400) {
                LOGGERICE("putdescription: %s %d via %s: generation rejected\n",commonLabel.data(),side,endpoint.host.c_str());
                // Side 1 cannot switch servers after accepting an offer. A
                // terminal rejection must also stop the other held polls.
                answerUndeliverable(true);
                return RendezvousAttemptResult::Failed;
                }
             else {
                if(code<0)
                    attempt.connectFailures.fetch_add(1,std::memory_order_release);
                LOGGERICE("putdescription: %s %d via %s: Http error\n",commonLabel.data(),side,endpoint.host.c_str());
                if(answerUndeliverable())
                    return RendezvousAttemptResult::Failed;
                if(!waitForAttempt(con,agent,attempt,5))
                    return stoppedAttempt(attempt);
                }

        const auto lastfailedtime=getConnectTime(allindex);
        if(lastfailedtime&&(time(nullptr)-lastfailedtime)>maxconnectionunused)
            setConnectTime(allindex,time(nullptr));
        }
    }

// Exchanges descriptions on the primary server, bringing in the others once
// it struggles. Returns false when no server and no LAN peer delivered.
static bool exchangeDescriptions(ICEConnect *con,juice_agent *agent,int allindex,
                                 std::string_view commonLabel,bool side,
                                 uint64_t generation) {
    if(side==givefirst&&!prepareLocalDescription(con,agent,commonLabel,side))
        return false;
    con->phase=side!=givefirst?GetDescription:PutDescription;
    const auto &endpoints=con->rendezvousEndpoints;
    const bool startEscalated=con->rendezvousEscalated.load()||
                              con->rendezvousExchangedUnconnected.load();
    const auto outcome=raceRendezvous(endpoints.size(),startEscalated,
        [&](size_t index,RendezvousAttempt &attempt) {
            const auto &endpoint=endpoints[index];
            if(side!=givefirst) {
                const auto offer=waitonDescription(agent,allindex,commonLabel,
                                                   side,endpoint,attempt);
                if(offer!=RendezvousAttemptResult::Won)
                    return offer;
                // Only the attempt that applied the offer gets here.
                if(!prepareLocalDescription(con,agent,commonLabel,side))
                    return RendezvousAttemptResult::Failed;
                con->phase=PutDescription;
                }
            return putDescription(allindex,agent,commonLabel,side,endpoint,attempt);
        },
        [&] {
            return con->isCurrentAgent(agent,generation)&&!con->endConnect.load()&&
                   !con->remoteDescriptionWasLocal.load();
        },
        [] { return elapsedRealtimeMilliseconds(); },
        [] { std::this_thread::sleep_for(std::chrono::milliseconds(250)); },
        [&] {
            LOGGERICE("rendezvous %s %d: %s struggling, also trying %zu more server(s)\n",
                      commonLabel.data(),side,endpoints.front().host.c_str(),
                      endpoints.size()-1);
        });
    if(outcome.escalated)
        con->rendezvousEscalated=true;
    if(!con->isCurrentAgent(agent,generation)||con->endConnect.load())
        return false;
    if(outcome.winner) {
        con->setActiveRendezvous(*outcome.winner);
        con->rendezvousExchangedUnconnected=true;
        LOGGERICE("rendezvous %s %d: descriptions exchanged via %s\n",commonLabel.data(),
                  side,endpoints[*outcome.winner].host.c_str());
        return true;
        }
    if(con->remoteDescriptionWasLocal.load()) {
        if(side!=givefirst&&!prepareLocalDescription(con,agent,commonLabel,side))
            return false;
        con->beginRendezvous();
        return true;
        }
    LOGGERICE("rendezvous %s %d: no server completed the exchange\n",commonLabel.data(),side);
    return false;
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
    if(con->useLocalDiscovery) {
        con->replaceLocalSignal(startLocalICESignal(
            allindex,agent,con->currentAgentGeneration(),commonLabel,side,
            host.pass,con->currentRendezvousGeneration()));
        }
    startPeerGenerationWatch(con,agent,allindex,commonLabel,side,hostname,
                             rendezvousPort);
    const uint64_t generation=con->currentAgentGeneration();
    if(!exchangeDescriptions(con,agent,allindex,commonLabel,side,generation)) {
        LOGGERICE("initAgent %s %d: description exchange failed\n",commonLabel.data(),side);
        return false;
        }
    if(!stillworking(allindex))
        return false;

    const bool readCandidates=!con->remoteDescriptionWasLocal.load();
    if(readCandidates) {
        con->startCandidateReader(getAddressesThread,agent,allindex,generation,
                                  std::string(commonLabel),side,
                                  con->activeRendezvousEndpoint());
        }
    LOGGERICE("initAgent %s %d: Before juice_gather_candidates\n",commonLabel.data(),side);
    con->phase=GatherCandidates;
    int ret=juice_gather_candidates(agent);
    if(con->endConnect.load()||!stillworking(allindex))
        return false;
    LOGGERICE("initAgent %s %d: After juice_gather_candidates(%p)=%d\n",commonLabel.data(),side,agent,ret);
    if(ret!=JUICE_ERR_SUCCESS)
        return false;
    // Callers shake hands as soon as this returns, so keep negotiating until
    // the START handshake has run (startDone) or the reader has ended. The
    // server may hold /address longer, until the peer's /done arrives; that
    // tail runs on in the background instead of delaying Clone reception.
    if(readCandidates) {
        while(!con->candidateReaderFinished.load(std::memory_order_acquire)&&
              con->startDone.test()&&con->isCurrentAgent(agent,generation)&&
              !con->endConnect.load())
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
        }
    return true;
  }
