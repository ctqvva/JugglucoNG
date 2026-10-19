#pragma once
#include <utility>
#include <algorithm>
#include <iterator>
#include <new>
#ifndef NOLOGS
#include "logs.hpp"
#endif
template <int N> struct max_size{ };
template <typename T,int N> struct CircleIterator ;
template <int N,typename T>
class CircularArray {
typedef T  (&dataAr_t)[N];
alignas(T) uint8_t   databuf[N*sizeof(T)];
//T  (&data)[N]=reinterpret_cast<T (&)[N]>(databuf); //Prevent default constucter
int pos=0;
constexpr dataAr_t data() {
    return reinterpret_cast<dataAr_t>(databuf); 
    }
public:
using value_type=T;
static constexpr const int max_size=N;
CircularArray() {
        LOGAR("CircularArray()");
        }
template <typename ... TS> 
        CircularArray(::max_size<N>,TS &&... args):pos(sizeof...(TS)) {
        new(data()) T[N]{std::forward<TS>(args)...};
        LOGAR("CircularArray ::max_size<N>");
        } 
template <typename ... TS> 
        CircularArray(TS && ... args):pos(sizeof...(TS)) {
        new(data()) T[N]{std::forward<TS>(args)...};
        LOGAR("CircularArray<TS");
        }
template <typename TE>
void push_back(TE &&el) {
        LOGGER("CircularArray::push_back %d\n",pos);
        set(pos++,std::forward<TE>(el));
        };
template <typename ... TS>
void emplace_back(TS && ... els) {
        LOGGER("CircularArray::emplace_back %d\n",pos);
        T*ptr=addItem();
        new (ptr) T(std::forward<TS>(els)...);
        };
T *addItem() {
        T*ptr=data()+(pos++%N);
        if(pos>N) 
            ptr->~T();
         return ptr;
        }
template <typename TE>
void set(int index,TE &&el) {
        T*ptr=data()+(index%N);
        if(index>=N) 
            ptr->~T();
        *ptr=std::forward<TE>(el);
        };
T &at(int index) {
        LOGGER("CircularArray::at(%d)\n",index);
        return data()[index%N];
        };
const T &at(int index) const {
        LOGGER("CircularArray::at(%d)\n",index);
        return data()[index%N];
        };
int capacity() const {
        return N;
        }
int size() const {
        return std::min(pos,N);
        }
int minindex() const {
        return std::max(pos-N,0);
        }
int maxindex() const {
        return pos;
        }
CircleIterator<T,N> begin()  {
        return CircleIterator<T,N>(*this,minindex());
        }
CircleIterator<T,N> end()  {
        return CircleIterator<T,N>(*this,maxindex());
        }
const CircleIterator<T,N> begin() const {
        return CircleIterator<T,N>(*this,minindex());
        }
const CircleIterator<T,N> end() const  {
        return CircleIterator<T,N>(*this,maxindex());
        }
T &operator[](int i) {
        return at(i);
        }
const T &operator[](int i) const {
        return at(i);
        }
void inc(){
     ++pos;
        }
friend bool operator== (const CircularArray& a, const CircularArray& b) { return a.data() == b.data()&&a.pos==b.pos; };
friend bool operator!= (const CircularArray& a, const CircularArray& b) { return !(a==b); };     
};
template <typename T,typename ...Ts> struct gettype{
    using Type=T;
    };
template<typename ...Ts>
using  gettype_t=typename gettype<Ts...>::Type;

//template <int N,typename ...Ts> CircularArray(::max_size<N>,Ts...) -> CircularArray<N,Ts...[0]>;
template <int N,typename ...Ts> CircularArray(::max_size<N>,Ts && ... ) -> CircularArray<N,gettype_t<Ts...>>;

template <typename T,int N>
struct CircleIterator 
{
    using iterator_category = std::random_access_iterator_tag;
    using difference_type   = std::ptrdiff_t;
    using value_type        = T;
    using pointer           = T*;  // or also value_type*
    using reference         = T&;  // or also value_type&
    CircleIterator(CircularArray<N,T> &data,int index=0):data(data),index(index)  { }

    reference operator*() const { return data.at(index); }
    pointer operator->() { return &data.at(index); }

    CircleIterator<T,N>& operator++() { 
        ++index; 
        return *this;
        }  

    CircleIterator<T,N> operator++(int) { 
        ++index;
        return CircleIterator<T,N>(data,index-1); 
        }
    CircleIterator<T,N>& operator--() { 
        --index; 
        return *this;
        }  

    CircleIterator<T,N> operator--(int) { 
        --index;
        return CircleIterator<T,N>(data,index+1); 
        }
   CircleIterator<T,N>& operator +=(int n) {
        index+=n; 
        return *this;
        }
   CircleIterator<T,N>& operator -=(int n) {
        index-=n; 
        return *this;
        }
    difference_type    operator-(const CircleIterator<T,N> &other) const {
        return index-other.index; 
        }
    CircleIterator<T,N>  operator+(int n) const {
        return CircleIterator<T,N>(data,index+n); 
        }
    CircleIterator<T,N>  operator-(int n) const {
        return CircleIterator<T,N>(data,index-n); 
        }
    T &operator[](int i) {
            return data.at(index+i);
            }
    const T &operator[](int i) const {
            return data.at(index+i);
            }

    friend bool operator== (const CircleIterator& a, const CircleIterator& b) { return a.data == b.data&&a.index==b.index; };
    friend bool operator!= (const CircleIterator& a, const CircleIterator& b) { return !(a==b); };     
    friend bool operator> (const CircleIterator& a, const CircleIterator& b) { return a.index>b.index; };
    friend bool operator< (const CircleIterator& a, const CircleIterator& b) { return a.index<b.index; };
    friend bool operator<= (const CircleIterator& a, const CircleIterator& b) { return a.index<=b.index; };
    friend bool operator>= (const CircleIterator& a, const CircleIterator& b) { return a.index>=b.index; };

private:
   CircularArray<N,T> &data;
   int index;
};

