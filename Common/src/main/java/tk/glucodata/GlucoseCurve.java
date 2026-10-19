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

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

import androidx.annotation.Keep;
import androidx.annotation.UiThread;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import tk.glucodata.nums.numio;
import tk.glucodata.settings.Settings;

import static tk.glucodata.Layout.getMargins;
import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static java.lang.Math.abs;
import static java.lang.System.currentTimeMillis;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.BuildConfig.SiBionics;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.MainActivity.systembarBottom;
import static tk.glucodata.MainActivity.systembarLeft;
import static tk.glucodata.MainActivity.systembarRight;
import static tk.glucodata.MainActivity.systembarTop;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.turnoffalarm;
import static tk.glucodata.NumberView.geteditview;
import static tk.glucodata.NumberView.geteditwearos;
import static tk.glucodata.NumberView.smallScreen;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getlabel;

/**
 * Was an OpenGL surface driving the native nanovg curve. Both the mobile and wear
 * ComposeHosts set this view to GONE before it is ever laid out, so no EGL context
 * was ever created and no frame was ever drawn — it only cost a surface per launch.
 *
 * What survives is the static screen-metrics surface ({@link #metrics},
 * {@link #getDensity}, {@link #dpToPx}, {@link #getwidth}, {@link #getheight}) plus
 * the legacy dialog helpers, all of which are still used by the legacy Java View
 * screens. No Kotlin/Compose code references any of it.
 *
 * getwidth()/getheight() return 0: they were only ever populated by the renderer's
 * onSurfaceChanged, which has not fired since the view started being hidden.
 */
public class GlucoseCurve extends View {
Button summarybutton=null;
boolean statspresent=false;
@Keep
void summaryready() {
    statspresent=true;
    if(doLog) {Log.i(LOG_ID,"summaryready");};;
    Applic.RunOnUiThread(()-> {
       if(doLog) {
           Log.i(LOG_ID,"UIThread summaryready");
           }
        Button tmp= summarybutton;
        summarybutton=null;
        if(tmp!=null) {
            {if(doLog) {Log.i(LOG_ID,"set Visible");};};
            tmp.setVisibility(VISIBLE);
            tmp.bringToFront();
            }
        });
   if(doLog) {
           Log.i(LOG_ID,"end summaryready");
           }
    
    }

@Keep
void showsensorinfo(String text,long sensorptr) {
    Applic.RunOnUiThread(()-> {
//        bluediag.showsensorinfo(text,(MainActivity )getContext());
        Sensors.show((MainActivity )getContext(),text,sensorptr);
        });
    }
static View[] reopen=new View[5];
static int reopennr=0;
    //    SearchLayout search;
    Layout search;
    private static final String LOG_ID = "GlucoseCurve";
static   public float smallfontsize;
    Calendar cal = Calendar.getInstance();
    static final int STEPBACK = 1;
    boolean waitnfc = false;
    static int height,width;

/**
 * The two ints that used to live on MyRenderer. No frame is drawn any more, but
 * these are still a live flag channel: ScanNfcV sets badscan/stepresult after an
 * NFC scan, and MainActivity.onBackPressed and Applic read stepresult to decide
 * whether back dismisses a scan result or finishes the activity.
 *
 * Kept as a field named `render` so the dozen existing `curve.render.*` call sites
 * keep their exact current meaning. Nothing sets stepresult implicitly now that
 * onDrawFrame is gone — only the explicit assignments do.
 */
public static final class ScanResultState {
    public int stepresult = 0;
    public int badscan = 0;
    }
public final ScanResultState render = new ScanResultState();



NumberView  numberview= new NumberView();

Layout numcontrol=null;
void startsearch() {
if(!isWearable) {
    MainActivity activity = (MainActivity) getContext();
    if(searchcontrol!=null) {
    Natives.stopsearch();
    searchcontrol.setVisibility(View.GONE);
    }
    if (search == null) {
    search = getsearchlayout(activity);
    } else {
    var labels=Natives.getLabels();
    if(!searchspinadap.getarray().equals(labels))  {
        searchspinadap.setarray(labels);
        searchspinner.setAdapter(searchspinadap);
        }
    search.setVisibility(View.VISIBLE);
         if(labelsel==Natives.getmealvar())
         mkmealsearch(activity);
    }
    if(!smallScreen)
        showkeyboard(activity);

    activity.setonback(()-> {
        activity.showui=false;
          activity.hideSystemUI();
        tk.glucodata.help.hidekeyboard(activity);
    search.setVisibility(View.GONE); 
        hidemealsearch();

    hidekeyboard(); reopener();
    if(LegacyScreensAccess.getMenusOn())
        LegacyScreensAccess.get().openMenus(activity);

    } );
    }
    }



static void hidesave(View v) {
    if(v.getVisibility()==VISIBLE) {
        reopen[reopennr++]=v;
        v.setVisibility(INVISIBLE);
        }
    }

void setminheight(View[] views,int minheight) {
    for(View v:views)
        v.setMinimumHeight(minheight);
    }
//void getnumcontrol(MainActivity activity,int width,int height) {
void getnumcontrol(MainActivity activity) {
   {if(doLog) {Log.i(LOG_ID,"getnumcontrol start");};};

   final int height=getHeight();
    if(numcontrol==null||numcontrol.getHeight()!=(height-systembarTop)) {
           ImageButton first=new ImageButton(activity);
           first.setImageResource( R.drawable.baseline_first_page_24);
           first.setOnClickListener(v-> {
        Natives.firstpage();
                  requestRender();
           });
        first.setContentDescription("First number");

           ImageButton back=new ImageButton(activity);
           back.setImageResource( R.drawable.baseline_arrow_back_24);
           back.setOnClickListener(v-> {
        Natives.backwardnumlist();
                  requestRender();

           });
        back.setContentDescription("Page back");
            


     ImageButton search=new ImageButton(activity);
     search.setImageResource( android.R.drawable.ic_menu_search);
        search.setContentDescription("Search");
//     search.setImageResource( android.R.attr.actionModeWebSearchDrawable);

           search.setOnClickListener(v-> {
               // Launch new Compose UI
               // android.content.Intent intent = new android.content.Intent(activity, tk.glucodata.ui.ComposeUIActivity.class);
               // activity.startActivity(intent);
               
//           hidesave(numcontrol);
//        startsearch();
//        selectnumbers();
//        hidesave(scansearch);
//        hidesave(streamsearch);
//        hidesave(calibratedsearch);
//        hidesave(historysearch);
           });
//    s/\(first[^6]*.6\)/(int)(\1)/g
    search.setPadding((int)(first.getPaddingLeft()*.69),(int)(first.getPaddingTop()*.69),(int)(first.getPaddingRight()*.69),(int)(first.getPaddingBottom()*.69));
     ImageButton closecontrol=new ImageButton(activity);

     closecontrol.setImageResource( android.R.drawable.ic_menu_close_clear_cancel);
           closecontrol.setOnClickListener(v-> {
           activity.doonback();
//           activity.poponback();
           });

    closecontrol.setContentDescription(activity.getString(R.string.closename));
    closecontrol.setPadding((int)(first.getPaddingLeft()*.69),(int)(first.getPaddingTop()*.69),(int)(first.getPaddingRight()*.69),(int)(first.getPaddingBottom()*.69));

           ImageButton next=new ImageButton(activity);

           next.setImageResource( R.drawable.baseline_arrow_forward_24);
        next.setContentDescription(getContext().getString(R.string.nextpage));
           next.setOnClickListener(v-> {
            Natives.forwardnumlist();
                 requestRender();

       });
           ImageButton last=new ImageButton(activity);
       last.setContentDescription("Last number");
           last.setImageResource( R.drawable.baseline_last_page_24);

           last.setOnClickListener(v-> {

        Natives.lastpage();
                  requestRender();
           });


        final View[] controls={first,
                back,
                search,
                closecontrol,
                next,
                last};
        final    int minheight=(int)(metrics.density*48.0f);
        setminheight(controls,minheight);
       numcontrol= new Layout(activity,(v,w,h) -> {
           final int width=getWidth();
//           final int height=getHeight();
           int columns=Natives.numcontrol(w,h);
         int bar=systembarTop;
         int over;
         if(bar>0) {
               v.setY(bar);
               over=height-bar;
               if(over>h)
                  over=h;
               }
          else  {
               if(height>h)
                  v.setY((height-h)/2.0f);
                over=h;
               }
        if(width>w) {
               if(columns==1)  {
                   v.setX(width-w-systembarRight);
                }
               else {

                   v.setX(((width-w-systembarRight+systembarLeft)/2.0f));
                   }
               }
                    requestRender();
         over-=systembarBottom;
            return new int[] {w,over};
          },new View[]{first}, new View[]{back}, new View[]{search},new View[]{closecontrol}, new View[]{next}, new View[]{last}
          );
           activity.addContentView(numcontrol, new ViewGroup.LayoutParams(WRAP_CONTENT,MATCH_PARENT));
       }
      else
        numcontrol.setVisibility(VISIBLE);
    activity.setonback(()-> {
        numcontrol.setVisibility(GONE);
        Natives.endnumlist();
         if(LegacyScreensAccess.getMenusOn()) {
             LegacyScreensAccess.get().openMenus(activity);
             }
        else
             requestRender();
         });
   {if(doLog) {Log.i(LOG_ID,"getnumcontrol end");};};
    }

    void showkeyboard(Activity context) {
       numberview.showkeyboard(context);
        }
    void hidekeyboard() {
        numberview.hidekeyboard();
            }
        
    /*OnBackPressedCallback callback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if ((render.stepresult & STEPBACK) == STEPBACK) {
                {if(doLog) {Log.d(LOG_ID,"GlucoseCurve: back");};};
                render.stepresult = 0;
//                ((MainActivity)getContext()).hideSystem=true;
                ((MainActivity)getContext()).hideSystemUI();
//                Natives.hidescanresults();
                requestRender();
            } else
                ((Activity) getContext()).finish();
        }
    };*/
static public DisplayMetrics metrics;
static public float getDensity() {
    if(metrics==null||metrics.density<=0.0f) {
        metrics= Applic.app.getResources().getDisplayMetrics();
        }
    return metrics.density;
    }
public GlucoseCurve(MainActivity context) {
    super(context);
    metrics= getResources().getDisplayMetrics();
    }

/**
 * No-op stand-in for the old GLSurfaceView requestRender. Roughly seventy call sites across
 * the legacy screens funnel here through MainActivity.requestRender(); there is no
 * longer anything to render, so this exists to keep them compiling rather than to
 * churn every one of them.
 */
public void requestRender() {
    }
    public static int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics);
    }

public static int getheight() {
    return height;
    }
public static int getwidth() {
    return width;
    }
static void setgeo(int w,int h) {
     if(smallScreen||w>=h) {
        width=w;
        height=h;
        }
    }

    long reldate;
     void startdatepick(long tim) {
        reldate=tim;
        numberview.getdateviewal((MainActivity)getContext(),tim,    (year,month,day)-> {
            Natives.movedate(reldate, year, month, day);
            requestRender();
        });

    }



private int[] minutes={-1,-1};


static String mktime(int hour,int min) {
     if(Applic.hour24)  {
                return String.format(usedlocale,"%02d:%02d", hour, min);
        }
    else {
            var daypart = (hour >= 12)?"pm":"am";
            var hour12 = hour % 12;
            if(hour12 == 0) hour12 = 12;
            return   String.format(usedlocale,"%d:%02d%s", hour12, min,daypart);
        }
    }

private void mktimedialog( Button but,final int num ,View parent) {
        but.setOnClickListener(
                v->  {
        parent.setVisibility(GONE);
        var keys=numberview.keyboard;
        if(keys!=null) {
            keys.setVisibility(INVISIBLE);
            }
        int starthour,startmin;
        if(minutes[num]>=0) {
            starthour=minutes[num]/60;
            startmin=minutes[num]%60;
            }
        else {
            cal.setTimeInMillis(currentTimeMillis());
             starthour=cal.get(Calendar.HOUR_OF_DAY);
             startmin=cal.get(Calendar.MINUTE);
             }
        numberview.gettimepicker((MainActivity)getContext(),starthour, startmin,
        (hour,min) -> {
            TextView text=((TextView) v);
                        text.setText(mktime( hour, min));
//            v.setBackgroundColor(Color.RED);
            text.setTextColor( Color.RED);
            text.setTypeface( null,Typeface.BOLD);
            text.setTextSize(COMPLEX_UNIT_PX,oldsize*1.5f);
                          minutes[num] = hour * 60 + min;
           },()-> {
            parent.setVisibility(VISIBLE);
                        if(keys!=null) {
                            keys.setVisibility(VISIBLE);
                        }


                    });
        });
    }


    EditText under,above;
    ImageButton prev=null,next=null;
   void searchaway() {
       if(search!=null) {
           search.setVisibility(GONE);
       hidemealsearch();
           hidekeyboard();
     if(searchcontrol!=null)
         searchcontrol.setVisibility(GONE);
        reopener();
       Natives.stopsearch();
       requestRender();
       }
   }
static void reopener() {
    for(int i=0;i<reopennr;i++)
        reopen[i].setVisibility(VISIBLE);
    reopennr=0;
    }
int labelsel=-1;
void clearsearch(View view) {
if(!isWearable) {
    if(mealquantity!=null) {
         mealquantity.setText("");
         mealingredient.setText("");
         }
      under.setText("0");
        above.setText("999");
        labelsel=searchspinner.getCount()-1;
        searchspinner.setSelection(labelsel);
        scansearch.setChecked(true);
        streamsearch.setChecked(true);
        calibratedsearch.setChecked(true);
        historysearch.setChecked(true);
    if(Applic.hour24)  {
        fromtime.setText("00:00");
        totime.setText("23:59");
        }
    else {
        fromtime.setText("12:00am");
        totime.setText("12:59pm");
        }
    fromtime.setTextColor(oldColors);
    totime.setTextColor(oldColors);
    totime.setTextSize(COMPLEX_UNIT_PX,oldsize);
    fromtime.setTextSize(COMPLEX_UNIT_PX,oldsize);
//    fromtime.setTypeface(null,0);
//    totime.setTypeface(null,0);
        minutes[0]=-1;
        minutes[1]=-1;

//    above.requestFocus();
    under.requestFocus();
    editfocus.setedittext(under);
    }
    }
View searchcontrol=null;
//    void search(View view) {
void search(boolean forward) {
    ((MainActivity)getContext()).hideSystemUI();
    if(smallScreen) {
        help.hidekeyboard((MainActivity)getContext());
        }
        float funder=0.0f;
        try {
                 funder= Float.parseFloat(under.getText().toString());
            } catch(Exception e) {};
        float fabove=0.0f;
        try {
                 fabove= Float.parseFloat(above.getText().toString());
            } catch(Exception e) {};
        float ingamount=0.0f;
        try {
            if(mealquantity!=null)
                ingamount= Float.parseFloat(mealquantity.getText().toString());
            } catch(Exception e) {};
        String ingsearch=null;
        if(mealingredient!=null) {
            ingsearch=mealingredient.getText().toString();
            if(ingsearch.length()==0)
                ingsearch=null;
                }
           int glsearch=((historysearch.isChecked()?0x40000002:0)| (scansearch.isChecked()?0x40000001:0))|(streamsearch.isChecked()?0x40000004:0)| (calibratedsearch.isChecked()?0x40000008:0);

       if(Natives.search(glsearch==0?labelsel:glsearch,funder,fabove,minutes[0],minutes[1],forward,ingsearch,ingamount)==0) {

           search.setVisibility(GONE);
           hidemealsearch();
           hidekeyboard();
           requestRender();
            MainActivity activity=(MainActivity)getContext();
        activity.poponback();
           if(searchcontrol==null) {

            /*  prev= new Button(activity); prev.setText("Earlier");*/

               prev=new ImageButton(activity);
               prev.setImageResource( R.drawable.baseline_navigate_before_white_36);
               prev.setOnClickListener(v-> {
                   if (0 == Natives.earliersearch()) {
                       next.setVisibility(VISIBLE);
                        requestRender();
                     }
                    else {
                       if (next.getVisibility() != VISIBLE)
                           searchaway();
                       else
                           v.setVisibility(INVISIBLE);
                    }
            });

    //           next= new Button(activity); next.setText("Later");
               next=new ImageButton(activity);
               next.setImageResource( R.drawable.baseline_navigate_next_white_36);
               next.setOnClickListener(v-> {if(0== Natives.latersearch()) {
                   prev.setVisibility(VISIBLE);
                   requestRender();
               }else
                   {
                    if(prev.getVisibility()!=VISIBLE)
                        searchaway();
                    else
                                v.setVisibility(INVISIBLE);
                   }
               });
         ImageButton closecontrol=new ImageButton(activity);
         closecontrol.setImageResource( android.R.drawable.ic_menu_close_clear_cancel);
             closecontrol.setOnClickListener(v-> {
             activity.poponback();
                    searchaway();
                     });
        int closepad= (int)(closecontrol.getPaddingLeft()*.67);
        int pad= (int)(closecontrol.getPaddingLeft()*.5);
        closecontrol.setPadding(closepad,closepad,closepad,closepad);
        next.setPadding(pad,pad,pad,pad);
        prev.setPadding(pad,pad,pad,pad);
        searchcontrol= new Layout(activity,(v,w,h) -> {
           int height=getHeight();
           int width=getWidth()-systembarLeft-systembarRight;
           int columns=Natives.getcolumns(w);
           if(height>h && width>w) {
               v.setY((height-h)*.5f);
               if(columns==1)  {
                   v.setX(width-w+systembarLeft);
                }
               else {
                   v.setX((width-w)*.5f+systembarLeft);
                   }
            }
          requestRender();
          return new int[] {w,h};
          },new View[]{prev},new View[] {closecontrol},new View[] {next});
               activity.addContentView(searchcontrol, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
           }

           prev.setVisibility(VISIBLE);
           next.setVisibility(VISIBLE);
           searchcontrol.setVisibility(VISIBLE);
           activity.setonback(this::searchaway);
           }
           else
        Applic.argToaster(getContext(), getContext().getString(R.string.notfound), Toast.LENGTH_SHORT);
        //((MainActivity)getContext()).curve.requestRender();
}

 
// Disable spell check (hex strings look like words to Android)

//RadioButton numbers;

    CheckBox scansearch,historysearch,streamsearch,calibratedsearch;

    Button fromtime, totime;

//https://gist.github.com/kakajika/a236ba721a5c0ad3c1446e16a7423a63
    /*
void radiolisten( RadioButton one,RadioButton other) {
         one.setOnClickListener(v-> {
             ((RadioButton) v).setChecked(true);
             other.setChecked(false);
             if(numbers.isChecked())
                 spinner.setVisibility(VISIBLE);
             else
                 spinner.setVisibility(GONE);

         });
}
*/
void selectnumbers() {
            scansearch.setChecked(false);
            historysearch.setChecked(false);
            streamsearch.setChecked(false);
            calibratedsearch.setChecked(false);
       //     spinner.setVisibility(VISIBLE);
        }
void glucoselisten(CompoundButton one) {
    one.setOnClickListener(v -> {
        if(historysearch.isChecked()||scansearch.isChecked()||streamsearch.isChecked()||calibratedsearch.isChecked()) {
            labelsel=searchspinner.getCount()-1;
            searchspinner.setSelection(labelsel);
            }
    });
}
Spinner searchspinner;
LabelAdapter<String> searchspinadap;
Spinner getsearchspinner(MainActivity context) {
if(searchspinner==null) {
    /*
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        searchspinner=new Spinner(context, null,0,R.style.MySpinnerStyle , -1);
    }
    else */
        searchspinner=new Spinner(context);
//        searchspinner=new Spinner(context, null,R.style.MySpinnerStyle );
//        searchspinner=new Spinner(context,R.style.spinner_style);
//        searchspinner=new Spinner(context,R.style.MySpinnerStyle2);

    int minheight=GlucoseCurve.dpToPx(48);
    searchspinner.setMinimumHeight(minheight);

    searchspinner.setContentDescription("Amount type selector");
   NumberView.avoidSpinnerDropdownFocus(searchspinner);
    searchspinadap= new LabelAdapter<String>(context,Natives.getLabels(),0);
        searchspinner.setAdapter(searchspinadap);
    searchspinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
       {if(doLog) {Log.i(LOG_ID,"onItemSelected "+position);};};
            if(position!=(searchspinner.getCount()-1)) {
                selectnumbers();
        if(position==Natives.getmealvar()) {
            mkmealsearch(context);
            }
        else
            hidemealsearch();
        }
        else
        hidemealsearch();
         labelsel=position;
        }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {
            labelsel=searchspinner.getCount()-1;

        } });
    searchspinner.clearAnimation();
    }
return searchspinner;

}
ColorStateList oldColors;
float oldsize;
Layout meallayout=null;
void hidemealsearch() {
    if(meallayout!=null) 
        meallayout.setVisibility(GONE);
    }
EditText mealingredient=null,mealquantity=null;
void mkmealsearch(MainActivity act) {
    if(meallayout==null) {
        var inglabel=getlabel(act,R.string.ingredient);
        var qualabel=getlabel(act,R.string.minimum);
        mealingredient=new EditText(act);
        mealingredient.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mealingredient.setImeOptions(editoptions);
        mealingredient.setOnFocusChangeListener(new editUIfocus());
        /*
    TextView.OnEditorActionListener     actlist= new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                act.hideSystemUI();
                 tk.glucodata.help.hidekeyboard(act);
                 {if(doLog) {Log.i(LOG_ID,"onEditorAction");};};
// hidekeyboard();
                return true;
               }
            return false;
            }};
        mealingredient.setOnEditorActionListener(actlist);
            */

//        mealingredient.setMinEms(10);
        ViewGroup.LayoutParams params= new ViewGroup.LayoutParams(  MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mealingredient.setLayoutParams(params);
        if(smallScreen) {
            mealquantity=geteditwearos(act);
            }
        else
            mealquantity=geteditview(act,new editclosefocus());
        mealquantity.setMinEms(3);
        int pad= (int)(tk.glucodata.GlucoseCurve.metrics.density*5);
             inglabel.setPadding(pad,0,pad,0);
        qualabel.setPadding(pad,0,pad,0);

//        mealquantity.setPadding(0,0,0,(int)metrics.density*8);
//        mealquantity.setMinEms(2);
//        mealingredient.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI| EditorInfo.IME_FLAG_NO_FULLSCREEN| EditorInfo.IME_ACTION_SEARCH);
//        mealquantity.setOnEditorActionListener(actlist);
        meallayout=new Layout(act,
(lay, w, h)->{
            lay.setY(systembarTop*4/5);
            lay.setX(systembarLeft);

        return new int[] {w- systembarLeft- systembarRight,h};
        },

      //pick.setPadding(MainActivity.systembarLeft,MainActivity.systembarTop,0,MainActivity.systembarBottom);
      //buttonlay.setPadding(0,MainActivity.systembarTop/2,MainActivity.systembarRight,MainActivity.systembarBottom);


        new View[] {inglabel,mealingredient,qualabel,mealquantity});
        act.addContentView(meallayout, new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            meallayout.setBackgroundColor(Applic.backgroundcolor);
        }
    else {
        meallayout.setVisibility(VISIBLE);
        meallayout.bringToFront();
        }
    

    }

private Layout getsearchlayout(MainActivity context) {
if(isWearable)
return null;
else {
    editfocus focus=new editfocus();
        if(smallScreen) {
            under=geteditwearos(context);
            }
        else
              under= geteditview(context,focus);
    int editwidth=(int)( metrics.density*48.0);
    under.setMinWidth(editwidth);


    TextView line=new TextView(context);line.setText("-");;

    if(smallScreen) {
            above=geteditwearos(context);
            }
        else
            above= geteditview(context,focus);
    above.setMinWidth(editwidth);
    scansearch=new CheckBox(context); scansearch.setText(R.string.scanname);
     historysearch=new CheckBox(context); historysearch.setText(R.string.historyname);
     streamsearch=new CheckBox(context); streamsearch.setText(R.string.streamname);
     calibratedsearch=new CheckBox(context); calibratedsearch.setText(R.string.calibrated);
     
        glucoselisten(scansearch) ;
        glucoselisten(historysearch) ;
        glucoselisten(streamsearch) ;
        glucoselisten(calibratedsearch) ;

    getMargins(calibratedsearch).rightMargin=getMargins(streamsearch).rightMargin =(int)metrics.density*10;

    fromtime =new Button(context); //fromtime.setText("00:00");
    TextView gline=new TextView(context);gline.setText(" - ");

      totime=new Button(context); //totime.setText("23:59"); 
      oldColors=totime.getTextColors();
      oldsize=totime.getTextSize();
        Button clear=new Button(context);clear.setText(R.string.resetname);

    View[] timeline={clear,fromtime,totime};

        ImageButton helpbut=new ImageButton(context);
        helpbut.setImageResource( android.R.drawable.ic_menu_help);

    helpbut.setContentDescription(getContext().getString(R.string.helpname));
        helpbut.setOnClickListener(v-> { 
            context.lightBars(false);
            help.help(R.string.searchhelp,context,l->context.lightBars(!getInvertColors( ))); 
        });

    Button cancel=new Button(context);cancel.setText(R.string.cancel);
    View[] buttonline={getsearchspinner(context),under,line,above};



    ImageButton backward=new ImageButton(context);

    backward.setContentDescription(getContext().getString(R.string.searchtopast));
        backward.setImageResource( R.drawable.baseline_navigate_before_white_36);

        backward.setOnClickListener(v-> {
        search(false);} );

        ImageButton forward=new ImageButton(context);

    forward.setContentDescription("Search forward");

        forward.setImageResource( R.drawable.baseline_navigate_next_white_36);
        forward.setOnClickListener(v-> {
        search(true) ;});

    View[] goline={backward,cancel,helpbut, forward};

    Layout layout=new Layout(context,(lay, w, h)->{
    int width=GlucoseCurve.getwidth();
    int height=GlucoseCurve.getheight();
if(!smallScreen) {
//    boolean rtl=Natives.getRTL();
    if(height>h&&width>w) {
           if(width>height) {
                lay.setY((height - h) / 2);
                
                int half= width / 2;
                int af=(half-w)/4;
                int posx= half - w-af;
                if(posx<0) {
                    posx=0;
                numberview.noroom=true;
                }
                else
                numberview.noroom=false;
                lay.setX(posx);
               {if(doLog) {Log.i(LOG_ID,"search h="+h+" height="+height+" w="+w+" width="+width+" posx="+posx);};};
                }
            else {

            int half=height/2;
            int af=(half-h)/4;
             var xpos= (width - w)/2;
                lay.setX(xpos);
                lay.setY(half - h-af);

         {if(doLog) {Log.i(LOG_ID,"search h="+h+" height="+height+" w="+w+" width="+width+" posx="+xpos);};};
            }
        }
    else {
        w=width;h=height;
            lay.setX(0);
            lay.setY(0);
        }
        }
    else {
      final var ypos=(int)((height-h)/2.5f);
        lay.setY(ypos);
      final var xpos=width>w?(width - w)/2:0;
        lay.setX(xpos);
      {if(doLog) {Log.i(LOG_ID,"smallScreen search h="+h+" height="+height+" w="+w+" width="+width+" posx="+xpos+" posy="+ypos);};};
        }
        return new int[] {w,h};
        },buttonline,new View[]{scansearch,calibratedsearch},new View[]{historysearch,streamsearch}, timeline,goline);

         mktimedialog( fromtime,0 ,layout);
      mktimedialog( totime,1 ,layout);
    context.addContentView(layout, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        layout.setBackgroundColor(Applic.backgroundcolor);

            
        cancel.setOnClickListener(v -> {
        context.doonback();
        } );
    
    clear.setOnClickListener(this::clearsearch );
    clearsearch(clear);
//       editfocus.setedittext(under);

    return layout;
    }
}


//Editable edit;



// Not View overrides — MainActivity drives these from its own onResume/onPause.
// They used to also be the GLSurfaceView lifecycle hooks; only the Applic
// bookkeeping was ever ours, and that is what is left.
public void onResume() {
    {if(doLog) {Log.i(LOG_ID,"onResume()");};};
    Applic app = Applic.app;

    app.setcurve(this);
    app.setmintime();
    }

public void onPause() {
    {if(doLog) {Log.i(LOG_ID,"onPause()");};};
     Applic app = Applic.app;
     app.cancelmintime();
     app.setcurve(null);
    }
static public void    doabout(MainActivity activity) {
if(!isWearable) {
    String about=activity.getString(R.string.about)+"<p>Version Code: "+ BuildConfig.VERSION_CODE+"<br>Version Name: "+ 
        BuildConfig.VERSION_NAME +"<br>"+Natives.getCPUarch()+"<br>Build time: "+ BuildConfig.BUILD_TIME +"</p>";
    
    help.help(about, activity);
    }
    }
void removeviews() {
        numberview.deleteviews();    
        searchspinner=null;
        if(search!=null) {
            removeContentView(search);
            search=null;
            }
        if(searchcontrol!=null) {
            removeContentView(searchcontrol);
            searchcontrol=null;
            }
       Applic.setremoveviews=false;
       }
}
