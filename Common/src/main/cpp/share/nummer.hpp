#pragma once
class nummer {
    int i;
    public:
    nummer(int b): i(b) {};
    size_t operator*() const {return  i;};
    nummer &operator++() {i++;return *this;};
    bool operator!=(const nummer &other) const { return i!=other.i; }
    };
