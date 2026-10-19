/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+ and           */
/*      Sibionics GS1Sb sensors.                                                     */
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
/*      Sat Jul 19 13:37:37 CEST 2025                                                */
package tk.glucodata;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.backgroundcolor;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Natives.getCalibrator;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getlabel;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import tk.glucodata.CaliPara;
import tk.glucodata.Layout;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.util;

import static android.graphics.Color.RED;
import static android.widget.LinearLayout.VERTICAL;

public class CalibrateList {
  static private final String LOG_ID ="CalibrateList";
static class CalView extends LinearLayout {
        TextView time;
        TextView cali;
        Button delete;
        CalView(Context context,TextView time,TextView cali,Button delete) {
            super(context);
            setOrientation(VERTICAL);
//                super(context,(x,w,h)->{ Log.i(LOG_ID,"h="+h+" w="+w); return new int[] {w,h}; },new View[]{time},new View[]{cali},new View[]{delete}); 
                this.time=time;
                this.cali=cali;
                this.delete=delete;
                setLayoutParams(new ViewGroup.LayoutParams(  ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                addView(time);
                addView(cali);
                addView(delete);
                }
        static CalView getCalView(Context context) {
                TextView time=new TextView(context);
                TextView cali=new TextView(context);
                Button delete = getbutton(context,R.string.delete);
                delete.setLayoutParams(new ViewGroup.LayoutParams(  ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new CalView(context,time,cali,delete);
                }
        };
/*
static class CalView extends TextView {
      public CalView(Context context) {
          super(context);
      }
      static CalView getCalView(Context context) {
          return new CalView(context);
      }
  }; */
   static  class CaliListViewHolder extends RecyclerView.ViewHolder {
       public CaliListViewHolder(CalView view) {
         super(view);
        }
   }

static  class CaliListViewAdapter extends RecyclerView.Adapter<CaliListViewHolder> {
        long sensorptr;
        boolean[] render;
        CaliListViewAdapter(long ptr,boolean[] render) {
             this.sensorptr=ptr;
              this.render=render;
             }

       @NonNull
      @Override
       public CaliListViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
           var cali=CalView.getCalView( parent.getContext());

           return new CaliListViewHolder(cali);
       }


      @Override
      public void onBindViewHolder(final CaliListViewHolder holder, int pos) {
         var cali=(CalView)holder.itemView;
         CaliPara para=new CaliPara();
         if(getCalibrator( sensorptr,pos,para)) {
                String calistr=String.format(Locale.US,"a=%.2f    b=%.2f",para.a,para.b);
                String timestr=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(para.time));

                cali.time.setText(timestr);
                cali.cali.setText(calistr);

                Log.i(LOG_ID,"pos="+pos+" "+timestr+" "+calistr);
                cali.delete.setOnClickListener( v -> { 
                    var context= cali.getContext();
                     var title=context.getString(R.string.delete_calibration);
                    Confirm.ask(context,title,timestr+(isWearable?"\n":"\n\n")+calistr , ()-> {
                        if(Natives.removeCalibrator( sensorptr, pos)) {
                           // notifyItemRemoved(pos); //position changes
                            notifyDataSetChanged();
                            render[0]=true;
                            }});
                    });
                cali.setOnClickListener( v -> { 
                       Natives.setStartTime(para.time-5*60000);
                       render[0]=true;
                        while(MainActivity.doonback() )
                            ;
                    });
                cali.cali.measure( ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
                cali.delete.measure( ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
                int left=cali.cali.getMeasuredWidth()-cali.delete.getMeasuredWidth();

                var mar=Layout.getMargins(cali.delete); 
                if(isWearable) {
                     mar.leftMargin= left/2;
                    mar.bottomMargin= (int)(5.0f*GlucoseCurve.metrics.density);
                   var width=GlucoseCurve.getwidth();
                    int horpad=(int)(0.4f*(width-cali.cali.getMeasuredWidth()));
                    cali.setPadding(horpad,0,0,0);
                    }
                 else {
                     mar.leftMargin= left*3/10;
                    mar.bottomMargin= (int)(10*GlucoseCurve.metrics.density);
                        }

                
                }

          }
           @Override
       public int getItemCount() {
            return Natives.calibrateNR( sensorptr);

           }

   }
static void show(MainActivity act, long sensorptr,View parent) {
     if(parent!=null) parent.setVisibility(GONE);
      act.lightBars(false);
      RecyclerView recycle = new RecyclerView(act);
      recycle.setHasFixedSize(true);
    recycle.setLayoutParams(new ViewGroup.LayoutParams(   ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      var lin=isWearable?new LinearLayoutManager(act):new GridLayoutManager(act,4);
      recycle.setLayoutManager(lin);
      boolean[] render={false};
      var caliadapt = new CaliListViewAdapter(sensorptr,render);
      recycle.setAdapter(caliadapt);
      var close=getbutton(act,R.string.closename);
      close.setOnClickListener( v -> { 
            MainActivity.doonback();
            });
      Layout layout;
    if(isWearable) {
        View[] firstrow=new View[]{close};
      layout=new Layout(act,(x,w,h)->{
             return new int[] {w,h};
               },firstrow,new View[]{recycle}); 
          }
     else {
       var help=getbutton(act,R.string.helpname);
       var settings=getbutton(act,R.string.settings);
        View[] firstrow=new View[]{help,settings,close};
      layout=new Layout(act,(x,w,h)->{
             return new int[] {w,h};
               },firstrow,new View[]{recycle}); 
        settings.setOnClickListener( v -> { 
            tk.glucodata.settings.Calibration.show(act,layout);
            });
        help.setOnClickListener(v-> tk.glucodata.help.help(R.string.calibrationslist,act));
        }
       layout.setBackgroundColor(backgroundcolor);
        float density=GlucoseCurve.metrics.density;
    layout.setPadding((int)(density*5.0)+MainActivity.systembarLeft,MainActivity.systembarTop,MainActivity.systembarRight+(int)(density*8.0),MainActivity.systembarBottom);

     act.addContentView(layout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
//      act.addContentView(layout, new ViewGroup.LayoutParams( WRAP_CONTENT, WRAP_CONTENT));
      MainActivity.setonback(()-> {
           act.lightBars(!getInvertColors( ));
           removeContentView(layout);
           if(render[0]) {
              act.requestRender();
              }
        parent.setVisibility(VISIBLE);

        });
      }

}
