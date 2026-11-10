#pragma once

#include <algorithm>
#include <charconv>
#include <cstddef>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

// HTTP framing is independent of TLS record boundaries. Only expose a body
// after its declared length, final chunk, or authenticated connection close.
// These requests carry small signaling/token responses, never glucose data.
class HttpResponseReader {
    static constexpr size_t maxHeaderBytes=32768,maxBodyBytes=1024*1024;
    enum class State { Headers, Fixed, Close, ChunkSize, ChunkData, ChunkEnd, Trailers, Done, Error };
    State state=State::Headers;
    std::string pending;
    size_t remaining=0,headerBytes=0;
    int code=-1;
    std::vector<char> body;

    static std::string_view trim(std::string_view text) {
        while(!text.empty()&&(text.front()==' '||text.front()=='\t')) text.remove_prefix(1);
        while(!text.empty()&&(text.back()==' '||text.back()=='\t')) text.remove_suffix(1);
        return text;
    }
    static bool equal(std::string_view a,std::string_view b) {
        return a.size()==b.size()&&std::equal(a.begin(),a.end(),b.begin(),[](char x,char y) {
            const auto lower=[](char c) {return c>='A'&&c<='Z'?c-'A'+'a':c;};
            return lower(x)==lower(y);
        });
    }
    bool fail() { state=State::Error;return false; }
    bool headers(std::string_view text) {
        const size_t lineEnd=text.find("\r\n");
        const auto status=text.substr(0,lineEnd);
        if(status.size()<12||(status.substr(0,9)!="HTTP/1.1 "&&status.substr(0,9)!="HTTP/1.0 ")||
           (status.size()>12&&status[12]!=' ')) return fail();
        const auto parsed=std::from_chars(status.data()+9,status.data()+12,code);
        if(parsed.ec!=std::errc{}||parsed.ptr!=status.data()+12||code<100||code>599) return fail();
        bool lengthPresent=false,chunked=false;
        size_t length=0;
        size_t offset=lineEnd==text.npos?text.size():lineEnd+2;
        while(offset<text.size()) {
            size_t end=text.find("\r\n",offset);
            if(end==text.npos) end=text.size();
            const auto line=text.substr(offset,end-offset);
            const size_t colon=line.find(':');
            if(colon==line.npos||colon==0||line.front()==' '||line.front()=='\t') return fail();
            const auto name=line.substr(0,colon),value=trim(line.substr(colon+1));
            if(equal(name,"Content-Length")) {
                const auto number=std::from_chars(value.data(),value.data()+value.size(),length);
                if(lengthPresent||value.empty()||number.ec!=std::errc{}||
                   number.ptr!=value.data()+value.size()||length>maxBodyBytes) return fail();
                lengthPresent=true;
            } else if(equal(name,"Transfer-Encoding")) {
                if(chunked||!equal(value,"chunked")) return fail();
                chunked=true;
            }
            offset=end+2;
        }
        if(chunked&&lengthPresent) return fail();
        if(code<200) {
            if(code==101||chunked||lengthPresent) return fail();
            state=State::Headers; // skip interim replies, preserving the deadline
        } else if(code==204||code==304) state=State::Done;
        else if(chunked) state=State::ChunkSize;
        else if(lengthPresent) {remaining=length;state=length?State::Fixed:State::Done;}
        else state=State::Close;
        return true;
    }
    bool consumeBody(size_t count) {
        if(count>maxBodyBytes-body.size()) return fail();
        body.insert(body.end(),pending.begin(),pending.begin()+count);
        pending.erase(0,count);
        return true;
    }
    bool advance() {
        for(;;) {
            switch(state) {
            case State::Headers: {
                const size_t end=pending.find("\r\n\r\n");
                if(end==pending.npos) return pending.size()>maxHeaderBytes-headerBytes?fail():true;
                if(end+4>maxHeaderBytes-headerBytes) return fail();
                headerBytes+=end+4;
                if(!headers(std::string_view(pending).substr(0,end))) return false;
                pending.erase(0,end+4);
                break;
            }
            case State::Fixed:
            case State::ChunkData: {
                const size_t count=std::min(remaining,pending.size());
                if(!consumeBody(count)) return false;
                remaining-=count;
                if(remaining) return true;
                state=state==State::Fixed?State::Done:State::ChunkEnd;
                break;
            }
            case State::Close:return consumeBody(pending.size());
            case State::ChunkSize: {
                const size_t end=pending.find("\r\n");
                if(end==pending.npos) return pending.size()>8192?fail():true;
                if(end>8192) return fail();
                auto number=std::string_view(pending).substr(0,end);
                number=number.substr(0,number.find(';'));
                const auto parsed=std::from_chars(number.data(),number.data()+number.size(),remaining,16);
                if(number.empty()||parsed.ec!=std::errc{}||parsed.ptr!=number.data()+number.size()||
                   remaining>maxBodyBytes-body.size()) return fail();
                pending.erase(0,end+2);
                state=remaining?State::ChunkData:State::Trailers;
                break;
            }
            case State::ChunkEnd:
                if(pending.size()<2) return true;
                if(pending.substr(0,2)!="\r\n") return fail();
                pending.erase(0,2);state=State::ChunkSize;break;
            case State::Trailers: {
                const size_t end=pending.find("\r\n");
                if(end==pending.npos) return pending.size()>maxHeaderBytes-headerBytes?fail():true;
                if(end+2>maxHeaderBytes-headerBytes) return fail();
                headerBytes+=end+2;
                pending.erase(0,end+2);
                if(!end) state=State::Done;
                break;
            }
            case State::Done:return true;
            case State::Error:return false;
            }
        }
    }
public:
    bool append(std::span<const char> bytes) {
        if(state==State::Error||bytes.size()>maxBodyBytes+maxHeaderBytes-pending.size()) return fail();
        pending.append(bytes.data(),bytes.size());
        return advance();
    }
    bool closed() {
        if(state==State::Close) state=State::Done;
        return complete();
    }
    bool complete() const { return state==State::Done; }
    int status() const { return code; }
    std::vector<char> takeBody() { return std::move(body); }
};
