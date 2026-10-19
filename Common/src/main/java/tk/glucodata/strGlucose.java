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
import androidx.annotation.Keep;

@Keep
public final class strGlucose {
	public	long time;
	public	String value;
	public	String sensorid;
	public	float rate;
   public int index;
   public int sensorgen2;
   public int trend;
	public strGlucose(long time,String value,String sensorid,float rate,int index,int gen) {
		this(time, value, sensorid, rate, index, gen, 0);
		};
	public strGlucose(long time,String value,String sensorid,float rate,int index,int gen,int trend) {
		this.time=time;
		this.value=value;
		this.sensorid=sensorid;
		this.rate=rate;
		this.index=index;
        this.sensorgen2=gen;
        this.trend=trend;
		};
};
