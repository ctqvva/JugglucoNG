/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2(+), Libre 3(+), Dexcom G7/ONE+,        */
/*      Sibionics GS1Sb and GS3, Accu-Chek SmartGuide, CareSens Air and              */
/*      Aidex X sensors.                                                             */
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
/*      Sun Nov 15 13:57:27 CET 2026                                                 */

#pragma once
#include "persistent_state.hpp"
#include <algorithm>
namespace libre2_processing {
// JugglucoNG: 24 hours past the nominal wear, as the vendor path gave by moving the start a day
// (the native code has 12). Start and nominal wear in the state stay as they are.
inline constexpr std::uint16_t wear_grace_minutes=24*60;
inline constexpr std::uint16_t processing_wear_minutes(std::uint16_t nominal){
    return std::uint16_t(std::min(unsigned(nominal)+wear_grace_minutes,0xffffu));
}
// JugglucoNG: the native code used nothing before elapsed minute 45 and showed nothing before
// the 60-minute warm-up. Ten minutes settle the sensor's currents; from then on a reading that
// looks like glucose is shown (numeric_pipeline.hpp checks it during the warm-up).
inline constexpr unsigned settle_minutes=10;
struct InputWindow {unsigned first{},last{};bool contains(unsigned i)const{return first<=last&&i>=first&&i<=last;}};
// Native 0x252500. Calibration starts at elapsed minute settle_minutes (native 45). The configured
// display warmup is separate. Historical alignment includes the two-minute
// delivery delay and uses the sensor clock's quarter-hour phase.
inline InputWindow eligible_input_window(bool history,unsigned latest_index,
        std::uint16_t sensor_minute,std::uint32_t elapsed_minute,std::uint16_t wear_minutes){
    using I=std::int64_t;const I index=latest_index;I first,last;
    if(history){
        const I aligned=I(elapsed_minute)-I((unsigned(sensor_minute)+13u)%15u);
        auto floor15=[](I n){return n>=0?n/15:-((-n+14)/15);};
        first=std::max(I(0),index-floor15(aligned-I(settle_minutes)));
        const I excess=aligned-I(wear_minutes);
        last=index-std::max(I(0),excess>0?(excess+14)/15:I(0));
    }else{
        first=std::max(I(0),index+I(settle_minutes)-I(elapsed_minute));
        last=std::min(index,index+I(wear_minutes)-I(elapsed_minute));
    }
    if(first>index||last<0||first>last)return {latest_index+1,0};
    return {unsigned(first),unsigned(last)};
}
// Native 0x259174. Input records have five uint16 words. Outside the eligible
// interval status is replaced with 8000, not OR-ed. The native code also let
// trend status bits 0, 2 or 6 on any one record invalidate the whole 16-minute
// trend window, current minute included. JugglucoNG keeps that flag on its own
// record: slope estimates that use it fail and leave the rate unknown.
inline void qualify_input_window(bool history,unsigned latest_index,unsigned maximum_index,
        std::uint16_t* records,std::uint16_t sensor_minute,std::uint32_t elapsed_minute,std::uint16_t wear_minutes){
    const auto window=eligible_input_window(history,latest_index,sensor_minute,elapsed_minute,wear_minutes);
    for(unsigned i=0;i<=maximum_index;++i){
        auto& status=records[i*5+4];
        if(i>latest_index||!window.contains(i))status=0x8000;
    }
}
inline std::uint16_t completed_history_index(std::uint16_t minute){return minute<2?0:std::uint16_t((minute-2)/15);}
inline std::uint32_t configuration_elapsed_minutes(std::span<const std::uint8_t,configuration_size> c){
    return (read_state_u32(c.data()+4)-read_state_u32(c.data()))/60u;
}
} // namespace libre2_processing
