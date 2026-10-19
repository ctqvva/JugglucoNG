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

import android.app.Application;

import static tk.glucodata.Natives.hasalarmloss;

/**
 * Abstract since it implements GlucoseAlarmHandler without handlealarm(): the base is never
 * instantiated on its own (nothing news it up), and requiring the override at compile time is
 * the point -- today a flavour could forget handlealarm() and nothing would say so until the
 * alarm fired.
 */
public abstract class SuperGlucoseAlarms implements GlucoseAlarmHandler {
    final private static String LOG_ID="SuperGlucoseAlarms";
public SuperGlucoseAlarms(Application context) {
	Notify.init(context);
	tk.glucodata.alerts.AlertRuntimeManager.INSTANCE.ensureMonitoring();
	// An unanswered alarm may already be outstanding from before a restart, so the
	// SMS watchdog has to come up with the alert runtime rather than on first edit.
	tk.glucodata.sms.SmsWatchdog.ensureRunning(context);
	}

//static final long showtime = Notify.glucosetimeout;


public	static long waitmmsec() {
	final short minutes = Natives.readalarmsuspension(4);
	final long mmsec = (minutes * 60 - 20) * 1000L;
	return mmsec;
	}


boolean saidloss = false;

    @Override
public  void setLossAlarm() {
    if(hasalarmloss()) {
         saidloss = false;
         final long nu = System.currentTimeMillis();
         SuperGattCallback.lastfoundL=nu;
//         Libre2GattCallback.oldtime = nu + Notify.glucosetimeout;
         LossOfSensorAlarm.setalarm(Applic.app, nu + waitmmsec());
       }
}

    @Override
public void setagealarm(final long numsec,long showtime) {
    Notify.stoplossalarm();

    saidloss = false;
    SuperGattCallback.lastfoundL=numsec;
   // Libre2GattCallback.oldtime = numsec + showtime;
    var oldtime = numsec + showtime;
    LossOfSensorAlarm.setalarm(Applic.app, oldtime);
    }



		}
