#pragma once
#include <string_view>
#include <initializer_list>

inline bool iceLogContainsCredentials(std::string_view message) {
    for(const std::string_view marker:{"Got username:","Got nonce:","username=\"",
                                      "password=\"","ufrag=\"","pwd=\"","username \"",
                                      "a=ice-pwd:","a=ice-ufrag:"})
        if(message.find(marker)!=std::string_view::npos) return true;
    return false;
}
