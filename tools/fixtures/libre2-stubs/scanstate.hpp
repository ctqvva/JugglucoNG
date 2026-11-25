// The four-slot layout of the real scanstate, in plain memory.
#pragma once
#include <array>
#include <cstring>
#include <vector>
#include "inout.hpp"
enum state : int { MESS, COMP, ATTE, FAKE };
struct scanstate {
    std::vector<unsigned char> map = std::vector<unsigned char>(16384);
    int freepos = 16;
    int &datpos(int i) { return reinterpret_cast<int *>(map.data())[i]; }
    data_t *get(int i) { return datpos(i) ? reinterpret_cast<data_t *>(map.data() + datpos(i)) : nullptr; }
    data_t *alloc(int len) {
        auto *d = reinterpret_cast<data_t *>(map.data() + freepos);
        d->len = len;
        freepos += (len + 4 + 7) / 8 * 8;
        return d;
    }
    void makenull(int i) { datpos(i) = 0; }
    void setpos(int i, data_t *d) { datpos(i) = int(reinterpret_cast<unsigned char *>(d) - map.data()); }
};
