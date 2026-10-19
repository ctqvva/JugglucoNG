#pragma once
#include <array>

struct ingredient_t {
    int unit;
    int used;
    float carb;
    std::array<char,40> name;
    };
