
#pragma once

template <int size> struct float_struct {
    class No16ByteFloat;
    using Type=No16ByteFloat;
    };

template <> struct float_struct<sizeof(float)> {
    using Type=float;
    };
template <> struct float_struct<sizeof(double)> {
    using Type=double;
    };
template <> struct float_struct<sizeof(double)!=sizeof(long double)?sizeof(long double):0xFFFFFFF> {
    using Type=long double;
    };
template <int size>
using float_s=float_struct<size>::Type;

typedef float_s<4> float32_t;
typedef float_s<8> float64_t;
typedef float_s<16> float128_t; 


