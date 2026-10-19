/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*      Thu Sep 18 15:34:39 CEST 2025                                                */


package tk.glucodata;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;


import static tk.glucodata.Log.doLog;

public class TimeZoneChangedReceiver extends BroadcastReceiver {
static final private String LOG_ID="TimeZoneChangedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
       if(doLog) {
            String action=intent.getAction();
            Log.i(LOG_ID,"onReceive "+((action!=null)?action:" null"));
            };
       Applic.setremoveviews=true;
       final var app=Applic.app;
       if(app!=null) {
           final var curve=app.curve;
           if(curve!=null) {
                  curve.removeviews();            
                  }
             else
                  Log.i(LOG_ID, "app.curve==null");
           }
        else {
            Log.i(LOG_ID, "Applic.app==null");
            
            }
       Notify.mkDateformat();
       bluediag.mktimeformat();
      }

    }

