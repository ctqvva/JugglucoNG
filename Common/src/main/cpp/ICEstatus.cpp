#include <stdio.h>
#include <string.h>
#include <string>
#include <string_view>
#include <memory>
#include "deleter.hpp"
#include "libjuice/include/juice/juice.h"
#include "datbackup.hpp"
#include "mirrorstatus.hpp"
#include "net/netstuff.hpp"
#include "net/ICE/ICEConnect.hpp"

extern mirrorstatus_t mirrorstatus[maxallhosts];
namespace {
void appendHtmlEscaped(std::string &out, std::string_view input) {
    out.reserve(out.size() + input.size());
    for (char c : input) {
        switch (c) {
            case '&': out.append("&amp;"); break;
            case '<': out.append("&lt;"); break;
            case '>': out.append("&gt;"); break;
            case '"': out.append("&quot;"); break;
            default: out.push_back(c); break;
        }
    }
}

std::string escapeHtml(std::string_view input) {
    std::string out;
    appendHtmlEscaped(out, input);
    return out;
}

const char *phaseName(enum Phase_t phase) {
    switch (phase) {
        case Start: return "Start";
        case GetDescription: return "Get description";
        case PutDescription: return "Put description";
        case GatherCandidates: return "Gather candidates";
        case FailedInitAgent: return "InitAgent failed";
        case NewConnection: return "New Connection";
        case SameConnection: return "Same Connection";
    }
    return "Unknown";
}
}
extern std::unique_ptr<const char[],deleter> ICEstatus(int allindex);

std::unique_ptr<const char[],deleter> ICEstatus(int allindex) {
         ICEConnect *con=static_cast<ICEConnect *>(connections[allindex]);
         if(!con)  {
                constexpr const char errormessage[]=R"(<h1>System ERROR, restart Juggluco</h1>)";
        return std::unique_ptr<const char[],deleter>(errormessage,deleter(errormessage));
                }
        passhost_t &host= getBackupHosts()[allindex];
        mirrorstatus_t &status=mirrorstatus[allindex];
    bool sendnums=false;
        bool sendstream=false;
        bool sendscans=false;
    const bool receives=host.receivefrom&2;
    if(host.index>=0) {
        updateone &send=backup->getupdatedata()->tosend[host.index];
        sendnums=send.sendnums;
        sendstream=send.sendstream;
        sendscans=send.sendscans;
        }
    const std::string escapedName = escapeHtml(host.getnameif());
    const std::string escapedIceLabel = escapeHtml(host.getICEname());
    const bool senderRunning = status.sender.running;
    const bool senderLocked = status.sender.locked;
    const bool senderShutdown = con->icedata[0].shutdown;
    const int receiveTid = status.receive.tid;
    const bool receiveInGetCom = status.receive.ingetcom();
    const bool receiveInInterpret = status.receive.ininterpret;
    const bool receiverShutdown = con->icedata[1].shutdown;
    static const char format[]=R"(<h1>ICE Connection %d: %s</h1><p><b>ICElabel</b>: "%s" <b>Side</b>=%d<br><b>Send to</b>: %s%s%s <b>Running</b>=%s <b>Locked</b>=%s  %s<br><b>Receive from</b>: %s    %d wait for commands: %s    <b>interpret</b>: %s %s</p><p><b>Phase</b>: )";
    const int headerLen = snprintf(nullptr, 0, format,
        allindex, escapedName.c_str(), escapedIceLabel.c_str(), host.side,
        sendnums?"Amounts ":"", sendscans?"Scans ":"", sendstream?"Stream ":"",
        boolstr[senderRunning], boolstr[senderLocked],
        senderShutdown?"shutdown":"",
        boolstr[receives], receiveTid,
        boolstr[receiveInGetCom], boolstr[receiveInInterpret],
        receiverShutdown?"shutdown":"");
    std::string out;
    out.reserve((headerLen > 0 ? static_cast<size_t>(headerLen) : 0) + 256);
    if (headerLen > 0) {
        out.resize(static_cast<size_t>(headerLen));
        snprintf(out.data(), out.size() + 1, format,
            allindex, escapedName.c_str(), escapedIceLabel.c_str(), host.side,
            sendnums?"Amounts ":"", sendscans?"Scans ":"", sendstream?"Stream ":"",
            boolstr[senderRunning], boolstr[senderLocked],
            senderShutdown?"shutdown":"",
            boolstr[receives], receiveTid,
            boolstr[receiveInGetCom], boolstr[receiveInInterpret],
            receiverShutdown?"shutdown":"");
    }
    out.append(phaseName(con->phase.load()));
    const auto [localAddr, remoteAddr] = con->selectedAddresses();
    if(!localAddr.empty()) {
        out.append("<br><b>Local:</b> ");
        appendHtmlEscaped(out, localAddr);
        if(!remoteAddr.empty()) {
            out.append("<br><b>Remote:</b> ");
            appendHtmlEscaped(out, remoteAddr);
          }
      }
   out.append("<br><b>ICE State:</b> ");
   out.append(juice_state_to_string(con->state.load()));
   if(con->endConnect) {
      out.append("<br>End connection");
     }
   if(con->isConnected) {
       out.append("<br>Connected");
       }
   if(const time_t connectTime=con->connectTime.load();connectTime) {
       char timebuf[32];
       if(ctime_r(&connectTime,timebuf)) {
           out.append("<br>");
           out.append(timebuf);
           }
       }
   char *buf=new(nothrow) char[out.size()+1];
   if(!buf)  {
        constexpr const char errormessage[]=R"(<h1>Error</h1>)";
        return std::unique_ptr<const char[],deleter>(errormessage,deleter(errormessage));
        }
   memcpy(buf, out.data(), out.size()+1);
   return std::unique_ptr<const char[],deleter> (buf,deleter(nullptr));
   }
