#pragma once
#include <stdio.h>
#include <stdint.h>
#include <math.h>

union shortfloat {
     uint16_t whole;
     struct {
        int16_t mantissa:12;
        int16_t exponent:4;
        };
     operator float() const {
        return getvalue();
        }
     float getvalue() const {
        switch(whole) {
                case 0x07FE:
                          if constexpr(std::numeric_limits<float>::has_infinity)
                                return std::numeric_limits<float>::infinity();
                case 0x0802:
                        if constexpr(std::numeric_limits<float>::has_infinity)
                                return -std::numeric_limits<float>::infinity();
                case 0x07FF:
                case 0x0801:
                case 0x0800: return NAN;
                default: return (float) mantissa*powf(10.0f,exponent);
                };

        }
     };

