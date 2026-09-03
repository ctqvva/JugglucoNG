
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
#pragma once
#include <condition_variable>
#include <memory>
#include "datbackup.hpp"
#include "logs.hpp"
#include <sys/socket.h>
#include <atomic>
#include <time.h>
#include "phase.h"
#include "net/netstuff.hpp"
#include "myfdsan.h"
#include "net/Connect.hpp"
#include "ICE_data.hpp"
#include "ICEConfig.hpp"
#define LOGGERICE(...) LOGGER("ICE: " __VA_ARGS__)
#define LOGARICE(...) LOGAR("ICE: " __VA_ARGS__)
extern bool initAgent(juice_agent *agent,int allindex);
extern juice_agent *createAgent(int allindex);

inline constexpr const juice_log_level_t juice_log_level=
#ifdef NOLOG
JUICE_LOG_LEVEL_NONE;
#else
//JUICE_LOG_LEVEL_WARN;
JUICE_LOG_LEVEL_VERBOSE;
#endif

extern int hostselect(std::string_view name);
class ICEConnect: public Connect {
    public:
std::atomic<time_t> connectTime{0};
std::atomic<juice_state_t> state{JUICE_STATE_DISCONNECTED};
std::atomic<Phase_t> phase{Start};
std::atomic_bool wakeReceiver{false};
std::atomic_bool receiverThreadRunning{false};
std::mutex receiveThreadMutex;
std::condition_variable receiveThreadCon; 
std::atomic_flag startSending{};
std::atomic_flag startDone{};
bool start_ack;
bool other_started;
void resetStart() {
 start_ack=false;
 other_started=false;
 bool old=startSending.test_and_set();
 startDone.test_and_set();
 LOGGER("resetStart flag was %d now %d\n",old,startSending.test());
 };
bool side;
std::atomic_bool endConnect{false};
std::atomic_bool isConnected{false};
std::mutex rendezvousMutex;
std::shared_ptr<std::atomic_bool> rendezvousCancellation{
        std::make_shared<std::atomic_bool>(false)};
std::mutex generationWatchMutex;
std::shared_ptr<std::atomic_bool> generationWatchCancellation{
        std::make_shared<std::atomic_bool>(false)};
std::mutex rendezvousGenerationMutex;
std::string rendezvousGeneration;
std::atomic<int> generationWatchCapability{0};
ICE_data   icedata[2]{{allindex,side},{allindex,!side}};
std::atomic<juice_agent*> agent;
std::atomic<uint64_t> agentGeneration{0};
std::atomic<int> selectedCloneTransport{clone_transport_unknown};
char sdp[JUICE_MAX_SDP_STRING_LEN];
int sdplen;
std::string rendezvousHost;
uint16_t rendezvousPort;
bool verifyRendezvousCertificate;

ICEConnect(int allindex,const passhost_t &host);
~ICEConnect() {
        finish=true;
        endConnectionHere();
        }
void endConnectionHere() {
       LOGGERICE("%d: endConnectionHere\n",side);
       isConnected=false;
       endConnect=true;
       cancelRendezvous();
       cancelGenerationWatch();
        icedata[1].setshutdown(); 
        icedata[0].setshutdown();
        startSending.clear();
        startSending.notify_all();
        startDone.clear();
        startDone.notify_all();
       }
bool requestReconnectIfCurrent(juice_agent_t *candidate,uint64_t generation) {
       if(!isCurrentAgent(candidate,generation))
           return false;
       bool expected=false;
       if(!endConnect.compare_exchange_strong(expected,true))
           return false;
       isConnected=false;
       cancelRendezvous();
       cancelGenerationWatch();
       icedata[1].setshutdown();
       icedata[0].setshutdown();
       startSending.clear();
       startSending.notify_all();
       startDone.clear();
       startDone.notify_all();
       return true;
       }
private:
//uint32_t initrunning=0;
std::atomic_flag initrunning{};
public:
std::shared_ptr<const std::atomic_bool> currentRendezvousCancellation() {
        std::lock_guard<std::mutex> lock(rendezvousMutex);
        return rendezvousCancellation;
        }
void cancelRendezvous() {
        std::lock_guard<std::mutex> lock(rendezvousMutex);
        rendezvousCancellation->store(true,std::memory_order_release);
        }
void beginRendezvous() {
        std::lock_guard<std::mutex> lock(rendezvousMutex);
        rendezvousCancellation->store(true,std::memory_order_release);
        rendezvousCancellation=std::make_shared<std::atomic_bool>(false);
        }
std::shared_ptr<const std::atomic_bool> beginGenerationWatch() {
        std::lock_guard<std::mutex> lock(generationWatchMutex);
        generationWatchCancellation->store(true,std::memory_order_release);
        generationWatchCancellation=std::make_shared<std::atomic_bool>(false);
        return generationWatchCancellation;
        }
void cancelGenerationWatch() {
        std::lock_guard<std::mutex> lock(generationWatchMutex);
        generationWatchCancellation->store(true,std::memory_order_release);
        }
bool prepareRendezvousGeneration();
std::string currentRendezvousGeneration();
virtual int setindex(int in) override{
        LOGGER("setindex(%d)\n",in);
        icedata[1].allindex=in;
        icedata[0].allindex=in;
        return Connect::setindex(in);
        }
int cloneTransportCode() const override {
        const juice_state_t currentState = state.load();
        return isConnected.load() &&
                       (currentState == JUICE_STATE_CONNECTED ||
                        currentState == JUICE_STATE_COMPLETED)
                   ? selectedCloneTransport.load()
                   : clone_transport_unknown;
        }
bool isCurrentAgent(juice_agent_t *candidate) const {
        return candidate && !finish.load() && agent.load() == candidate;
        }
bool isCurrentAgent(juice_agent_t *candidate, uint64_t generation) const {
        return isCurrentAgent(candidate) && agentGeneration.load() == generation;
        }
uint64_t currentAgentGeneration() const {
        return agentGeneration.load();
        }
void advanceAgentGeneration() {
        static std::atomic<uint64_t> nextGeneration{1};
        agentGeneration.store(nextGeneration.fetch_add(1), std::memory_order_release);
        }
bool claimReceiverThread() {
        bool expected=false;
        return receiverThreadRunning.compare_exchange_strong(expected, true);
        }
void releaseReceiverThread() {
        receiverThreadRunning.store(false);
        }
 int newConnection(int allindex) {
        setindex(allindex);
        if(initrunning.test_and_set()) {
            LOGGERICE("newConnection(%d) already running\n",allindex);
            return 0;
            } 

        LOGGER("start newConnection(%d)\n",allindex);
        destruct _{[this]{initrunning.clear();}};
        cancelRendezvous();
        cancelGenerationWatch();
        auto wasagent=agent.exchange(nullptr);
        advanceAgentGeneration();
        if(wasagent) {
            LOGGER("1: juice_destroy(%p)\n",wasagent);
            #ifndef NOLOG
            juice_set_log_level(JUICE_LOG_LEVEL_VERBOSE);
            #endif
            juice_destroy(wasagent);
            #ifndef NOLOG
            juice_set_log_level(juice_log_level);
            #endif
            }
        icedata[1].reCreated(); 
        icedata[0].reCreated(); 
        resetStart();
        wakeReceiver=false;
        selectedCloneTransport.store(clone_transport_unknown);
        generationWatchCapability.store(0,std::memory_order_release);
        isConnected=false;
        endConnect=false;
        beginRendezvous();
        if(!prepareRendezvousGeneration()) {
                phase=FailedInitAgent;
                endConnect=true;
                return -1;
                }
        phase=NewConnection;

        juice_agent *theagent=createAgent(allindex);
        if(!theagent) {
                phase=FailedInitAgent;
                endConnect=true;
                cancelGenerationWatch();
                return -1;
                }
        // Publish the new agent before negotiation begins. libjuice can invoke
        // state and candidate callbacks synchronously from initAgent().
        agent.store(theagent);
       if(!initAgent(theagent,allindex)) {
                phase=FailedInitAgent;
                cancelGenerationWatch();
                LOGGER("end ICEConnect::newConnection failed allindex=%d, juice_destroy(%p)\n",allindex,theagent);
                auto current=agent.exchange(nullptr);
                advanceAgentGeneration();
                if(current) {
            #ifndef NOLOG
                    juice_set_log_level(JUICE_LOG_LEVEL_VERBOSE);
                #endif
                    juice_destroy(current);
            #ifndef NOLOG
                    juice_set_log_level(juice_log_level);
                #endif
                    }
                endConnect=true;
                return -1;
                }
        LOGGERICE("end ICEConnect::newConnection(%d) agent=%p\n",allindex,agent.load());
        return 1;
        }

public:
 void notifyReceive() {
     icedata[0].notifyReceive(); 
     icedata[1].notifyReceive(); 
     }
void sayEndConnection(){
        endConnectionHere();
        icedata[1].shutDown(agent.load());
        icedata[0].end(agent.load());
        LOGGERICE("%d: ICEConnect::sayEndConnection allindex=%d agent=%p\n",side,allindex,agent.load());
        }
void endConnection() override{
        sayEndConnection();
//        auto wasagent=agent; agent=nullptr;
        if(initrunning.test_and_set()) {
                LOGGERICE("%d: ICEConnect::endConnection allindex=%d agent=%p, but initrunning\n",side,allindex,agent.load());
                return;
                }
        destruct _{[this]{initrunning.clear();}};
        LOGGERICE("%d: ICEConnect::endConnection allindex=%d agent=%p\n",side,allindex,agent.load());
        juice_agent *wasagent=agent.exchange(nullptr);
        advanceAgentGeneration();
        if(wasagent) {
            LOGGER("endConnection: juice_destroy(%p)\n",wasagent);
            #ifndef NOLOG
            juice_set_log_level(JUICE_LOG_LEVEL_DEBUG);
            #endif
            juice_destroy(wasagent);
            #ifndef NOLOG
            juice_set_log_level(juice_log_level);
            #endif
            } 
#ifndef NOLOG
        LOGGERICE("%d: end ICEConnect::endConnection allindex=%d set agent=%p\n",side,allindex, wasagent);
#endif
        }
int  connect(const passhost_t *pass) {
        icedata[0].reStarted();
        icedata[1].reStarted();
        int index=gethostindex(pass);
        if(endConnect)   {
            LOGGERICE("allindex=%d %s %d: ICE::Connect::connect endConnection\n",allindex,pass->getICEname().data(),pass->side);
            return newConnection(index);
            }
        if(index!=allindex)   {
            LOGGERICE("%s %d: ICE::Connect::connect allindex old=%d new=%d\n",pass->getICEname().data(),pass->side,allindex,index);
            return newConnection(index);
            }
        if(!isConnected)   {
            if(agent.load()&&!endConnect.load()) {
                LOGGERICE("allindex=%d %s %d: ICE::Connect::connect keep pending agent\n",allindex,pass->getICEname().data(),pass->side);
                return 0;
                }
            LOGGERICE("allindex=%d %s %d: ICE::Connect::connect !isConnection\n",allindex,pass->getICEname().data(),pass->side);
            return newConnection(index);
            }
        if(!agent.load())   {
            LOGGERICE("allindex=%d %s %d: ICE::Connect::connect agent==NULL\n",allindex,pass->getICEname().data(),pass->side);
            return newConnection(index);
            }
        else {
            LOGGERICE("allindex=%d side=%d connect anew\n",allindex,pass->side);
            phase=SameConnection;
             }
     {
           std::lock_guard<std::mutex> lck(receiveThreadMutex);
           wakeReceiver=true;
           receiveThreadCon.notify_one();
          }
        return -2;
        }
virtual int makeconnection2(passhost_t *pass,char stype) override {
        LOGGER("makeconnection2 %s\n",pass->getICEname().data());
        sleep(1);
        connect(pass);
        if(!agent.load())
            return -1;
        return shakehands(pass,stype);
        }

virtual ssize_t  r_sendni(const void *buf, size_t len) override{
    if(!isConnected)  {
          LOGGERICE("ICEConnect::r_sendni(%p,%d)= not connected\n",buf,len);
          return -1;
          }
    auto ret= icedata[1].senddata(agent.load(), (const char *)buf,len);
    LOGGERICE("ICEConnect::r_sendni(%p,%d)=%d\n",buf,len,ret);
    return ret;
    }
virtual ssize_t  r_recvni(void *buf, size_t len) override {
        if(!isConnected)  {
            LOGGERICE("ICEConnect::r_recvni(%p,%d) not connected\n",buf,len);
            return -1;
        }
       auto ret=icedata[1].receive(agent.load(),(char *)buf, len);
        LOGGERICE("ICEConnect::r_recvni(%p,%d)=%d\n",buf,len,ret);
        return ret;
        }
virtual ssize_t  s_sendni(const void *buf, size_t len) override{
     if(!isConnected) {
        LOGGERICE("ICEConnect::s_sendni(%p,%d) not connected\n",buf,len);
        return -1;
        }
    auto ret= icedata[0].senddata(agent.load(), (const char *)buf,len);
    LOGGERICE("ICEConnect::s_sendni(%p,%d)=%d\n",buf,len,ret);
    return ret;
	}
virtual ssize_t  s_recvni(void *buf, size_t len) override {
       if(!isConnected) {
           LOGGERICE("ICEConnect::s_recvni(%p,%d) not connected\n",buf,len);
           return -1;
           }
        auto ret= icedata[0].receive(agent.load(),(char *)buf, len);
        LOGGERICE("ICEConnect::s_recvni(%p,%d)=%d\n",buf,len,ret);
        return ret;
        }


virtual void shutdownReceiver() override {
        if(!backup)
            return;
#ifndef NOLOG
        const passhost_t &host= getBackupHosts()[allindex];
        LOGGERICE("%s %d shutdownReceiver()\n",host.getICEname().data(),host.side);
#endif
        icedata[1].shutDown(agent.load());
        }
virtual void restartReceiver() override {
    if(!backup)
        return;
#ifndef NOLOG
    const passhost_t &host= getBackupHosts()[allindex];
    LOGGERICE("%s %d restartReceiver()\n",host.getICEname().data(),host.side);
#endif
    icedata[1].shutDown(agent.load());
    }
virtual void restartSender() override {
    if(!backup)
        return;
#ifndef NOLOG
    const passhost_t &host= getBackupHosts()[allindex];
    LOGGERICE("%s %d restartSender()\n",host.getICEname().data(),host.side);
#endif
    icedata[0].shutDown(agent.load());
    }
virtual void shutdownSender() override {
        if(!backup)
            return;
#ifndef NOLOG
        const passhost_t &host= getBackupHosts()[allindex];
        LOGGERICE("%s %d shutdownSender()\n",host.getICEname().data(),host.side);
#endif
        icedata[0].shutDown(agent.load());
        }

virtual  void  closeReceiverConnection() override {
        if(!backup)
            return;
#ifndef NOLOG
        const passhost_t &host= getBackupHosts()[allindex];
        LOGGERICE("%s %d closeReceiverConnection()\n",host.getICEname().data(),host.side);
#endif
        icedata[1].shutDown(agent.load());
      }


virtual  void  closeSenderConnection() override {
        if(!backup)
            return;
#ifndef NOLOG
        const passhost_t &host= getBackupHosts()[allindex];
        LOGGERICE("%s %d closeSenderConnection()\n",host.getICEname().data(),host.side);
#endif
        icedata[0].shutDown(agent.load());
      }


int getIdent()  const {
    if(!agent.load())
        return -1;
    return (int)(uint64_t)agent.load();
    }
virtual  int  getReceiverIdent() const override {
    return getIdent(); 
    };
virtual  int  getSenderIdent() const override {
    return getIdent(); 
    };
uint64_t senderConnectionGeneration() const override {
    return currentAgentGeneration();
    }


 virtual  bool  isConnectedReceiver() const override {
        if(icedata[1].shutdown) {
                return false;
                }
        if(!agent.load())
                return false;
        juice_state_t state = juice_get_state(agent.load());
        return state == JUICE_STATE_COMPLETED||state == JUICE_STATE_CONNECTED;
        };
 virtual  bool  isConnectedSender() const override {
        if(icedata[0].shutdown) {
                return false;
                }
        if(!agent.load())
                return false;
        juice_state_t state = juice_get_state(agent.load());
        return state == JUICE_STATE_COMPLETED||state == JUICE_STATE_CONNECTED;
        };
virtual void setReceiverTimeouts() override {
    }
virtual void setSenderTimeouts() override {
    }

void notConnected() {
    isConnected=false;
    LOGGERICE("%d notConnected, shutdown=true\n",side);
    }
void setConnected() {
    isConnected=true;
    LOGGERICE("%d setConnected, shutdown=false\n",side);
    icedata[0].shutdown=false;
    icedata[1].shutdown=false;
    }
    void receiverThread(int allindex);
 };
