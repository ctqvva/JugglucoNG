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
/*      Fri Nov 22 12:21:00 CET 2024                                                 */


#pragma once

#define curveNID NID_X9_62_prime256v1
typedef struct bignum_st BIGNUM;
typedef struct ec_point_st EC_POINT;
inline const EC_GROUP* getgroup() {
	static const EC_GROUP* ec_group = EC_GROUP_new_by_curve_name(curveNID);
	return ec_group;
	}
struct Point {
   EC_POINT* data;
   Point(): data(EC_POINT_new(getgroup())) { }
explicit   Point(EC_POINT* in):data(in) {}
   Point(const Point &in):data(EC_POINT_dup(in,getgroup())) { }
   Point(Point &&in) {
   	std::swap(in.data,data);
   	}
static const Point getconst(const EC_POINT* in) {
	return Point(const_cast<EC_POINT*>(in));
	}
operator EC_POINT*() {
	return data;
	}
operator const EC_POINT*() const {
	return data;
	}
     ~Point() {
	EC_POINT_free(data);
     	}
    };
template <typename BN>
struct GenNumber {
static_assert(std::is_same<const BN,const BIGNUM>::value);
   BN* data;
   GenNumber(): data(BN_new()) {
   	};
 explicit   GenNumber(BN* in): data(in) { };
   GenNumber(const GenNumber &in): data(BN_dup(in)) { };
   GenNumber(GenNumber &&in) {
   	std::swap(in.data,data);
	}

operator BN*() {
	return data;
	}
operator const BN*() const {
	return data;
	}
   ~GenNumber(){
	   BN_free(const_cast<BIGNUM*>(data));
	};
   };
typedef GenNumber<BIGNUM> Number;
typedef GenNumber<const BIGNUM> ConstNumber;
