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

import static tk.glucodata.Applic.usedlocale;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;

import android.bluetooth.BluetoothDevice;
import androidx.annotation.Keep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
//import dalvik.system.DexPathList;
import dalvik.system.BaseDexClassLoader;


public class Log {
/**
 * Compile-time ceiling. Builds that set {@code doLog=0} strip logging entirely and can never
 * turn it back on at runtime.
 */
private static final boolean buildAllowsLog=BuildConfig.doLog==1;

/**
 * Whether log calls should do any work at all.
 *
 * <p>Not a constant: the native side keeps the real switch ({@code Natives.islogging()}, backed
 * by the {@code nolog} setting and the Debug Settings toggle) and drops everything when it is
 * off. Mirroring it here lets the {@code if(doLog)} guards short-circuit <em>before</em> the
 * message string is concatenated and pushed across JNI, which is pure waste on the BLE paths.
 *
 * <p>Seeded optimistically so logging works during startup, before natives are loaded; refreshed
 * by {@link #refreshDoLog()} once they are, and again whenever the user flips the toggle.
 */
public static volatile boolean doLog=buildAllowsLog;

/**
 * Re-read the native logging switch. Call after natives load and after any
 * {@code Natives.dolog(...)} change.
 */
public static void refreshDoLog() {
	if(!buildAllowsLog) {
		doLog=false;
		return;
		}
	if(!Applic.Nativesloaded) {
		doLog=true;
		return;
		}
	try {
		doLog=Natives.islogging();
		}
	catch(Throwable e) {
		android.util.Log.e(LOG_ID,"islogging() unavailable, keeping logging on");
		doLog=true;
		}
	}
static final private String LOG_ID="Log";
private static void log(String type,String one,String two) {
   	if(!Applic.Nativesloaded)  {
		android.util.Log.e(one, two);
		return;
		}

	if(doLog) {
	 StringBuilder sb = new StringBuilder();
	  sb.append(type);
	  sb.append("/");
	  sb.append(one);
	  sb.append(" ");
	  sb.append(two);
	  sb.append("\n");
	  try {
	  	String str=sb.toString();
	  	Natives.log(str);
		}
	catch(Throwable e) {
		android.util.Log.e(LOG_ID, android.util.Log.getStackTraceString(e));
		android.util.Log.e(one, two);
		}
		}
	  }
public static void info(String str) {
	SensorTraceRing.add(' ',null,str);
	if(doLog) {
		if(Applic.Nativesloaded)  {
			Natives.log(str+'\n');
			}
		else
			android.util.Log.e(LOG_ID, str);
		}
	}
   public static void stack(String id,Throwable e) {
	stack(id,"",e);
   	}
public static String stackline(Throwable e) {
	String str=android.util.Log.getStackTraceString(e);
	final int len=str.length();
	if(len>100) {
		int end=str.indexOf("\n");
		if(end<len)  {
			if(end<20)
				end=100;
			return str.substring(0,end);
			}
		}
	return str;
	}
public static String stackstring(String mess,Throwable e) {
	String str=android.util.Log.getStackTraceString(e);
	String all=str==null?mess+" Exception":mess+" "+str;
	return all;
	}
   public static void stack(String id,String mess,Throwable e) {
	String all=Log.stackstring(mess,e);
	if(!doLog||!Applic.Nativesloaded)
		android.util.Log.e(id,all);
	else
		log("E",id,all);

   	}
public static void e(String one,String two) {
	SensorTraceRing.add('E',one,two);
	if(!doLog||!Applic.Nativesloaded)
		android.util.Log.e(one,two);
	else
		log("E",one,two);
		};
/**
 * Not captured into {@link SensorTraceRing}: unlike the tagged calls, this one is the reason
 * its own message does not exist yet, and capturing would mean running String.format on every
 * call with logging off. No driver uses it -- they format at the call site and pass the result
 * to {@link #i} -- so there is nothing here worth that.
 */
public static void format(String format, Object... args) {
	if(doLog) {
		String form=String.format(usedlocale,format,args);
		if(Applic.Nativesloaded) 
			Natives.log(form);
		else
			android.util.Log.i(LOG_ID,form);
		}
	}

public static void annolog(String mess) {
   final StackTraceElement[] stack=new Throwable().getStackTrace();
   final var aan=stack[1];
   final String filename=aan.getFileName();
   final int ln=aan.getLineNumber() ;
   final String cl=aan.getClassName();
   final String fun=aan.getMethodName() ;
   final String tag=cl+"."+fun+"("+filename+":"+ln+"): ";
   Natives.log("FIND "+tag+mess);
  // Natives.log(all);
   }
//s/public static void \([a-z]\).*\(log.*\);/public static void \1(String one,String two) 	{ if(Applic.Nativesloaded) {\2 else android.util.Log.\1(one,two);};/g

public static void d(String one,String two) 	{ SensorTraceRing.add('D',one,two); if(doLog) {if(Applic.Nativesloaded) {log("D",one,two);} else android.util.Log.d(one,two);}};
public static void w(String one,String two) 	{ SensorTraceRing.add('W',one,two); if(doLog) {if(Applic.Nativesloaded) {log("W",one,two);} else android.util.Log.w(one,two);}};
public static void v(String one,String two) 	{ SensorTraceRing.add('V',one,two); if(doLog) {if(Applic.Nativesloaded) {log("V",one,two);} else android.util.Log.v(one,two);}};
public static void i(String one,String two) 	{ SensorTraceRing.add('I',one,two); if(doLog) {if(Applic.Nativesloaded) {log("I",one,two);} else android.util.Log.i(one,two);}};
public static void  showbytes(String mess,byte[] ar) {
	if(doLog)
		{if(doLog){Natives.showbytes(mess,ar);};}
	}



//invoke-virtual {v0}, Landroid/bluetooth/BluetoothDevice;->getName()Ljava/lang/String;
/*
public static void showLoader(ClassLoader loader) {
   try {
      dalvik.system.BaseDexClassLoader base;
      String className = "dalvik.system.BaseDexClassLoader";
     Class<?> cls = Class.forName(className);
      Field nameField = cls.getDeclaredField("pathList");
      nameField.setAccessible(true);
     var pathlist =  nameField.get(loader);
     Log.i("showLoader",pathlist.toString());
     }
     catch(Throwable th) {
       stack(LOG_ID,"showLoader",th);
      }
   }
public static void showLoader() {
   showLoader(Log.class.getClassLoader());
   }

public static void showScanfilters( List<ScanFilter> mScanFilters ) {
   if(mScanFilters==null) { 
      Log.i( "ScanFilters","null");
      return;
      }
      
   for(var f: mScanFilters) {
      Log.i( "ScanFilters",f.toString());
      }
   }

public static void showScanSettings(  ScanSettings mScanSettings) {
   Log.i("ScanSettings","ScanMode="+mScanSettings.getScanMode());
   Log.i("ScanSettings","CallbackType="+mScanSettings.getCallbackType());
   Log.i("ScanSettings","ScanResultType="+mScanSettings.getScanResultType());
 //  Log.i("ScanSettings","MatchMode="+mScanSettings.getMatchMode());
//   Log.i("ScanSettings","NumOfMatches="+mScanSettings.getNumOfMatches());
   Log.i("ScanSettings","Legacy="+mScanSettings.getLegacy());
   Log.i("ScanSettings","Phy="+mScanSettings.getPhy());
   Log.i("ScanSettings","ReportDelayMillis="+mScanSettings.getReportDelayMillis());
   }

*/

};
