#pragma once
#include <charconv>
#include <optional>
#include <string>
#include <string_view>
#include <cstdint>
#include <utility>
#include <initializer_list>

struct TurnToken {
    std::string username, password;
    int ttlSeconds = 0;
};

// Bounded JSON reader for a provider's token response. Extract only root
// credentials / TTL, skip nested ice_servers, and never log response contents.
class TurnTokenReader {
    std::string_view text;
    size_t position = 0;
    void whitespace() {
        while(position<text.size()&&(text[position]==' '||text[position]=='\r'||
              text[position]=='\n'||text[position]=='\t')) ++position;
    }
    bool take(char c) {
        whitespace();
        if(position==text.size()||text[position]!=c) return false;
        ++position;return true;
    }
    bool string(std::string &value) {
        if(!take('"')) return false;
        value.clear();
        while(position<text.size()) {
            unsigned char c=text[position++];
            if(c=='"') return true;
            if(c<0x20) return false;
            if(c=='\\') {
                if(position==text.size()) return false;
                c=text[position++];
                switch(c) {
                    case '"':case '\\':case '/':break;
                    case 'b':c='\b';break; case 'f':c='\f';break;
                    case 'n':c='\n';break; case 'r':c='\r';break; case 't':c='\t';break;
                    case 'u': {
                        if(text.size()-position<4) return false;
                        unsigned code=0;
                        for(int i=0;i<4;++i) {
                            const char digit=text[position++];
                            const int n=digit>='0'&&digit<='9'?digit-'0':
                                digit>='a'&&digit<='f'?digit-'a'+10:
                                digit>='A'&&digit<='F'?digit-'A'+10:-1;
                            if(n<0) return false;
                            code=(code<<4)|n;
                        }
                        // TURN token credentials are ASCII. Reject unsupported
                        // escapes rather than silently changing a credential.
                        if(code>127) return false;
                        c=static_cast<unsigned char>(code);break;
                    }
                    default:return false;
                }
            }
            if(value.size()>=1024) return false;
            value.push_back(static_cast<char>(c));
        }
        return false;
    }
    bool skip(unsigned depth=0) {
        if(depth>16) return false;
        whitespace();
        if(position==text.size()) return false;
        if(text[position]=='"') {std::string ignored;return string(ignored);}
        for(const auto &pair:{std::pair{'{','}'},std::pair{'[',']'}}) {
            if(text[position]!=pair.first) continue;
            ++position;
            if(take(pair.second)) return true;
            do {
                if(pair.first=='{') {std::string key;if(!string(key)||!take(':')) return false;}
                if(!skip(depth+1)) return false;
                if(take(pair.second)) return true;
            } while(take(','));
            return false;
        }
        const size_t begin=position;
        while(position<text.size()&&text[position]!=','&&text[position]!='}'&&text[position]!=']'&&
              text[position]!=' '&&text[position]!='\r'&&text[position]!='\n'&&text[position]!='\t') ++position;
        const auto value=text.substr(begin,position-begin);
        if(value=="true"||value=="false"||value=="null") return true;
        // Unknown numeric fields need not be interpreted. JSON's finite set
        // of numeric characters is sufficient here; TTL is parsed strictly.
        return !value.empty()&&value.find_first_not_of("-+0123456789.eE")==std::string_view::npos;
    }
public:
    explicit TurnTokenReader(std::string_view value):text(value) {}
    std::optional<TurnToken> read() {
        if(text.size()>65536||!take('{')) return {};
        TurnToken token;
        bool user=false,password=false,ttl=false;
        if(take('}')) return {};
        do {
            std::string key;
            if(!string(key)||!take(':')) return {};
            if(key=="username") {if(user||!string(token.username)) return {};user=true;}
            else if(key=="password") {if(password||!string(token.password)) return {};password=true;}
            else if(key=="ttl") {
                if(ttl) return {};
                whitespace();
                std::string quoted;
                std::string_view number;
                // Twilio returns TTL as a JSON string on the live API; some
                // clients / fixtures expose it as a number. Accept both, with
                // the same strict integer and lifetime bounds.
                if(position<text.size()&&text[position]=='"') {
                    if(!string(quoted)) return {};
                    number=quoted;
                } else {
                    const size_t begin=position;
                    if(!skip()) return {};
                    number=text.substr(begin,position-begin);
                }
                const auto result=std::from_chars(number.data(),number.data()+number.size(),token.ttlSeconds);
                if(result.ec!=std::errc{}||result.ptr!=number.data()+number.size()) return {};
                ttl=true;
            } else if(!skip()) return {};
            if(take('}')) {
                whitespace();
                const auto valid=[](const std::string &value) {
                    return !value.empty()&&value.find_first_of(std::string_view("\0\r\n",3))==std::string::npos;
                };
                if(position!=text.size()||!user||!password||!ttl||token.ttlSeconds<60||
                   token.ttlSeconds>86400||!valid(token.username)||!valid(token.password)) return {};
                return token;
            }
        } while(take(','));
        return {};
    }
};
