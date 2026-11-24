#pragma once
#include <cstdint>
#include <cstdlib>
struct alignas(8) data_t {
    int len;
    signed char bytes[1];
    int size() const { return len; }
    const signed char *data() const { return bytes; }
    signed char *data() { return bytes; }
};
