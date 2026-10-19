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


package tk.glucodata

import android.graphics.Canvas
import android.graphics.Color.*
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.pow
import kotlin.math.sqrt

    

    

public class CommonCanvas {

	companion object {
		private const val LOG_ID = "CommonCanvas"

		@JvmStatic
        @JvmOverloads
		public fun drawarrow(
			canvas: Canvas,
			paint: Paint,
			density: Float,
			ratein: Float,
			getx: Float,
			gety: Float,
            extraScale: Float = 1.0f
		): Boolean {
			if (!ratein.isNaN()) {
				// Optical Stroke Arrow (Notification Version)
				// Matches TrendIndicator.kt logic (Detached Chevrons)
				
				// 0. Normalize Rate
                val unit = Natives.getunit()
                val normalizedRate = if (unit == 1) ratein * 18.0182f else ratein

				// 1. Rotation (User Tuned 25f)
				val rotation = (-normalizedRate * 25f).coerceIn(-90f, 90f)

				// 2. Scale
				val speed = kotlin.math.abs(normalizedRate)
				val scale = (1.0f + (speed * 0.12f).coerceAtMost(0.5f)) * extraScale

				// 3. Specs (User Tuned + Visible Shaft)
				val baseSize = density * 24f
				val strokeWidth = baseSize * 0.12f // 12%
                
                val showDouble = speed > 2.0f
                
                // Dynamic Length: Longer for Double Heads
                // Fast: Shortened Arrow (~0.35w) + Gap + HeadDepth
                val lenFactor = if (showDouble) 0.35f else 0.6f
                val len = baseSize * lenFactor * scale 
                
                val headSpan = baseSize * 0.55f // Wide Head
                val headDepth = headSpan / 2
                val opticalShift = headDepth * 0.2f
                
                canvas.save()
                canvas.translate(getx, gety) // Center
                canvas.rotate(rotation)
                
                // Style
                val originalStyle = paint.style
                val originalWidth = paint.strokeWidth
                val originalCap = paint.strokeCap
                val originalJoin = paint.strokeJoin
                
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeWidth
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                
                if (showDouble) {
                    // FAST: Shaft + Gap + Double Floating Chevrons (---- >>)
                    val gap = headDepth * 0.6f
                    val shaftGap = headDepth * 0.6f
                    
                    val tipX = len/2 - opticalShift
                    
                    // Front Head
                    val frontHeadTip = tipX
                    val frontHeadWing = tipX - headDepth
                    
                    // Back Head 
                    val backHeadTip = frontHeadWing - gap
                    val backHeadWing = backHeadTip - headDepth
                    
                    // Shaft
                    val shaftEnd = backHeadWing - shaftGap
                    val shaftStart = -len/2 - opticalShift
                    
                    // 1. Shaft (Only if room)
                    if (shaftEnd > shaftStart) {
                        canvas.drawLine(shaftStart, 0f, shaftEnd, 0f, paint)
                    }
                    
                    // 2. Back Head
                    val pBack = Path().apply {
                        moveTo(backHeadWing, -headSpan/2)
                        lineTo(backHeadTip, 0f)
                        lineTo(backHeadWing, headSpan/2)
                    }
                    
                    // 3. Front Head
                    val pFront = Path().apply {
                        moveTo(frontHeadWing, -headSpan/2)
                        lineTo(frontHeadTip, 0f)
                        lineTo(frontHeadWing, headSpan/2)
                    }
                    
                    canvas.drawPath(pBack, paint)
                    canvas.drawPath(pFront, paint)
                    
                } else {
                    // NORMAL STATE: Arrow with Shaft (->)
                    val pMain = Path().apply {
                        val tipX = len/2 - opticalShift
                        val baseX = -len/2 - opticalShift
                        val wingX = tipX - headDepth
                        
                        // Wings
                        moveTo(wingX, -headSpan/2)
                        lineTo(tipX, 0f)
                        lineTo(wingX, headSpan/2)
                        
                        // Shaft
                        moveTo(baseX, 0f)
                        lineTo(tipX, 0f)
                    }
                    canvas.drawPath(pMain, paint)
                }
                
                // Restore
                paint.style = originalStyle
                paint.strokeWidth = originalWidth
                paint.strokeCap = originalCap
                paint.strokeJoin = originalJoin
                
                canvas.restore()
                
				return true
			}
			return false
		}
		/*
	@JvmStatic
public fun drawarrow(canvas: Canvas,paint:Paint,density:Float, ratein:Float, getx:Float, gety:Float):Boolean {
	if(!ratein.isNaN()) {
		val rate= Natives.thresholdchange(ratein);
		val x1:Double= (getx-density*40.0)
		val y1:Double= (gety+rate*density*30.0)

		var rx: Double=getx-x1
		var ry:Double=gety-y1
		val rlen= sqrt(rx.pow(2.0) + ry.pow(2.0))
		 rx/=rlen
		 ry/=rlen

		val l:Double=density*12.0;

		val addx= l* rx;
		val addy= l* ry;
		val tx1=getx-2*addx;
		val ty1=gety-2*addy;
		val xtus:Float= (getx-1.5*addx).toFloat();
		val ytus:Float= (gety-1.5*addy).toFloat();
		val hx=ry;
		val hy=-rx;
		val sx1:Float= (tx1+l*hx).toFloat();
		val sy1:Float= (ty1+l*hy).toFloat();
		val sx2:Float = (tx1-l*hx).toFloat();
		val sy2:Float= (ty1-l*hy).toFloat();
              paint.strokeWidth = density.toFloat()*5.0f
	    canvas.drawLine(x1.toFloat(), y1.toFloat(), xtus, ytus,paint)
		canvas.drawPath(Path().apply {
			moveTo(sx1,sy1) ;
			lineTo(getx,gety);
			lineTo(sx2,sy2);
			lineTo( xtus,ytus);
            		close()
			},paint)
           return true
		}
    return false
	}
	*/

private fun paintArrow( canvas: Canvas, paint: Paint, density: Float, rate: Float, x1: Double, y1: Double, getx: Double, gety: Double) {

		var rx: Double = getx - x1
		var ry: Double = gety - y1
		val rlen = sqrt(rx.pow(2.0) + ry.pow(2.0))
		rx /= rlen
		ry /= rlen

		val l: Double = density * 12.0;

		val addx = l * rx;
		val addy = l * ry;
		val tx1 = getx - 2 * addx;
		val ty1 = gety - 2 * addy;
		val xtus: Float = (getx - 1.5 * addx).toFloat();
		val ytus: Float = (gety - 1.5 * addy).toFloat();
		val hx = ry;
		val hy = -rx;
		val sx1: Float = (tx1 + l * hx).toFloat();
		val sy1: Float = (ty1 + l * hy).toFloat();
		val sx2: Float = (tx1 - l * hx).toFloat();
		val sy2: Float = (ty1 - l * hy).toFloat();
		paint.strokeWidth = density.toFloat() * 5.0f
		canvas.drawLine(x1.toFloat(), y1.toFloat(), xtus, ytus, paint)
		canvas.drawPath(Path().apply {
			moveTo(sx1, sy1);
			lineTo(getx.toFloat(), gety.toFloat());
			lineTo(sx2, sy2);
			lineTo(xtus, ytus);
			close()
		}, paint)
	}

		//{{x -> h - (4 h)/Sqrt[16 + 9 rate^2], y -> h + (3 h rate)/Sqrt[16 + 9 rate^2]}, {x -> h + (4 h)/Sqrt[16 + 9 rate^2], y -> h - (3 h rate)/Sqrt[16 + 9 rate^2]}}

	@JvmStatic public fun testcircle( canvas: Canvas, paint: Paint,density:Float) {
		val height = canvas.height.toDouble(); //assume square
		val half = height * .5f
/*		paint.setStyle(Paint.Style.STROKE)
		paint.setStrokeWidth(height*.05f);
		canvas.drawCircle(half,half,half, paint); */
		val rate=0.0f;
		paintArrow(canvas, paint, density, rate, 0.0, half, height, half);
		}

/*{{x -> -((2 h w)/Sqrt[16 h^2 + 9 rate^2 w^2]), 
  y -> -((3 h rate w)/(2 Sqrt[16 h^2 + 9 rate^2 w^2]))}, {x -> (
   2 h w)/Sqrt[16 h^2 + 9 rate^2 w^2], 
  y -> (3 h rate w)/(2 Sqrt[16 h^2 + 9 rate^2 w^2])}} */

		@JvmStatic
		public fun drawarrowcircle(
			canvas: Canvas,
			paint: Paint,
			density: Float,
			ratein: Float
		): Boolean {
			if (!ratein.isNaN()) {
				val height: Double = canvas.height.toDouble();
				val width: Double = canvas.width.toDouble()
				val rate = Natives.thresholdchange(ratein)
//				val common = 1.0 / sqrt(16.0 + 9.0 * rate.pow(2));
				val common =  width*height/sqrt(16*height.pow(2.0)+9 * rate.pow(2)*width.pow(2))
				val xcom = (2.0 * common);
				val halfW = width * .5;
				val x1 = halfW - xcom;
				val x2 = halfW + xcom;
				val ycom = (1.5 * rate * common);
				val halfH = height * .5;
				val y1 = halfH + ycom;
				val y2 = halfH - ycom;
				paintArrow(canvas, paint, density, rate, x1, y1, x2, y2);
				return true
			}
			return false
		}
	}
}
