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
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ConnectReceiver extends BroadcastReceiver {
static final private String LOG_ID="ConnectReceiver";
        @Override
  public void onReceive(Context context, Intent intent) {
      var blue=SensorBluetooth.blueone;
      if(blue!=null) {
            String id=intent.getAction();
            if(doLog) {Log.i(LOG_ID,"onReceive "+id);};
            if(id!=null) {
                  blue.connectNamedDevice(id,0);
                  return;
                  }
           }
      else {
          if (doLog) {
              Log.i(LOG_ID, "onReceive no active Sensor");
              }
       }

      }

//static private PendingIntent onalarm=null ;
/*
TODO:
What with multiple DexCom sensors?
- Previous alarm will be concalled by second sensor, so that first will not be issued.
- not all sensors should be connected, only the one setting the alarm. */

    }
