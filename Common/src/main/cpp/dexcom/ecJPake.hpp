/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Nov 22 12:20:22 CET 2024                                                 */


#pragma once
#ifdef TEST
#define LOGGER(...) fprintf(stderr,__VA_ARGS__)
#else
#include "logs.hpp"
#endif
#include <stdint.h>
typedef struct bignum_st BIGNUM;
typedef struct ec_point_st EC_POINT;
struct Point;
struct PCert {
   const EC_POINT* pubkey1;
   const EC_POINT*  pubkey2;
   const BIGNUM* hash;
   void free();
   ~PCert() ;
  PCert(): pubkey1(nullptr), pubkey2(nullptr),hash(nullptr)
  { 
      LOGGER("PCert() this=%p\n",this);
  };
  PCert(PCert &&cert): pubkey1(cert.pubkey1), pubkey2(cert.pubkey2), hash(cert.hash) {
      LOGGER("PCert(&&) this=%p\n",this);
      cert.pubkey1=nullptr;
      cert.pubkey2=nullptr;
      cert.hash=nullptr;
      }
 PCert(const PCert &cert); 
  void frombytes(const uint8_t *bytes);
template <typename T>
void frombytes(T *bytes){
    frombytes(reinterpret_cast<const uint8_t *>(bytes));
}
/*  void fill(const EC_POINT * p1,const EC_POINT * pub_key,const BIGNUM *  private_key,const BIGNUM * exponent);
  void fill(const EC_POINT * p1, EC_POINT * &&pub_key,const BIGNUM *  private_key,const BIGNUM * exponent);
  void fill(const EC_POINT * p1,Point &&pub_key,const BIGNUM *  private_key,const BIGNUM * exponent);
  void fill(const EC_POINT * p1,const Point &pub_key,const BIGNUM *  private_key,const BIGNUM * exponent);
*/
void fill(const EC_POINT * p1, const EC_POINT * pub_key,const BIGNUM *  private_key,const BIGNUM * rannum);

  bool valid() const {
      return pubkey1!=nullptr;
       }
  std::array<uint8_t,160> byteify() const;
  };
typedef struct ec_key_st EC_KEY;

struct KeyPair {
   EC_KEY *keyptr; 
   bool invalid() const {
        return keyptr==nullptr;
        }
   KeyPair();
   ~KeyPair();
   const BIGNUM *getprivate() const;
   const EC_POINT *getpublic() const;
   };
