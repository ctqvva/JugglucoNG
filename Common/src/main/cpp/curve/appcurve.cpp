#include "SensorGlucoseData.hpp"
#include "config.h"
#include "nanovg.h"
#include "sensoren.hpp"
#include <GLES2/gl2.h>
#define NANOVG_GLES2_IMPLEMENTATION
#include "nanovg_gl.h"
#include "nanovg_gl_utils.h"
#include "net/backup.hpp"
#include "nums/numdata.hpp"
#include "settings/settings.hpp"

#include "JCurve.hpp"
#include "gluconfig.hpp"
#include "jugglucotext.hpp"
#include "misc.hpp"

extern double calibrateNow(const SensorGlucoseData *sens,
                           const ScanData &value);

extern Sensoren *sensors;
extern int *numheights;
extern float tapx, tapy;
extern void speak(const char *message);
static int nrmenu = 0, selmenu = 0;
struct lastscan_t scantoshow = {-1, nullptr};
extern bool makepercetages();

extern bool hasnetwork();

bool bluetoothEnabled();

extern int showui;

float JCurve::getboxwidth(const float x) {
  return std::max((float)(dwidth - x - smallsize), dwidth * .25f);
}
#include "numdisplay.hpp"
extern vector<NumDisplay *> numdatas;
#include "numiter.hpp"
NumIter<Num> *numiters = nullptr;
int basecount;

#include "numhit.hpp"
extern NumHit newhit;
extern Num newnum;
#ifdef NOTALLVIES
int betweenviews = 60 * 30;
time_t nexttimeviewed = 0;
#endif

extern bool selshown;
extern bool speakout;

bool emptytap = false;
extern bool fixatex, fixatey;
extern std::vector<shownglucose_t> shownglucose;
extern void setusedsensors(uint32_t nu);

extern void mkheights();
void updateusedsensors(uint32_t nu) {
  static int wait = 0;
  static int32_t waslast = -1;
  int newlast = sensors->last();
  if (waslast != newlast || !wait) {
    LOGAR("updateusedsensors");
    waslast = newlast;
    setusedsensors(nu);
    wait = 100;
    mkheights();
  } else
    wait--;
}

static void printGlString(const char *name, GLenum s) {
#ifndef NDEBUG
  const char *v = (const char *)glGetString(s);
  if (v)
    LOGGER("GL %s: %s\n", name, v);
#endif
}

int numlist = 0;
bool alarmongoing = false;
int JCurve::showoldscan(NVGcontext *avg, uint32_t nu) {
  if (!scantoshow.scan)
    return 0;
  if ((nu - scantoshow.showtime) >= 60) {
    scantoshow = {-1, nullptr};
    return -1;
  }
  numlist = 0;
  const SensorGlucoseData *hist =
      sensors->getSensorData(scantoshow.sensorindex);
  showscanner(avg, hist, scantoshow.scan - hist->beginscans(), nu);
  return 1;
}

struct {
  float x = -300.0f, y = -300.0f;
  std::chrono::time_point<std::chrono::steady_clock> time;
} prevtouch;
#include "strconcat.hpp"
class histgegs {
  // const int sensorindex;
  const SensorGlucoseData *hist;
  time_t nu;

public:
#ifndef DONTTALK
  strconcat text;
#endif
  histgegs(const SensorGlucoseData *hist)
      : hist(hist) /*,glu(glu),tim(tim)*/, nu(time(nullptr))
#ifndef DONTTALK
        ,
        text(getsensorhelp(usedtext->menustr0[3], ": ", "\n", "\n", " "))
#endif
  {

    prevtouch.time = chrono::steady_clock::now();
    LOGGER("histgegs %s", ctime(&nu));
  }
  strconcat getsensorhelp(string_view starttext, string_view name1,
                          string_view name2, string_view sep1, string_view sep2,
                          string_view endstr = "") {
    char starts[50], ends[50], pends[50];
    //   const sensor *sensor=sensors->getsensor(sensorindex);
    time_t stime = hist->getstarttime(), etime = hist->officialendtime();
    // time_t reallends=hist->isLibre3()?etime:sensor->maxtime();
    time_t reallends = hist->expectedEndTime();
    char lastscanbuf[50], lastpollbuf[50];
    time_t lastscan = hist->getlastscantime();
    time_t lastpolltime = hist->getlastpolltime();
    return strconcat(
        string_view(""), starttext, name1, hist->showsensorname(), name2,
        usedtext->sensorstarted, sep2,
        string_view(starts, appcurve.datestr(stime, starts)),
        !hist->isLibre2() ? "" : sep1,
        !hist->isLibre2() ? "" : usedtext->lastscanned,
        !hist->isLibre2() ? "" : sep2,
        !hist->isLibre2()
            ? ""
            : string_view(lastscanbuf, appcurve.datestr(lastscan, lastscanbuf)),
        lastpolltime > 0
            ? strconcat(string_view(""), sep1, usedtext->laststream, sep2)
            : "",
        lastpolltime > 0
            ? string_view(lastpollbuf,
                          appcurve.datestr(lastpolltime, lastpollbuf))
            : string_view("", 0),
        nu < etime ? static_cast<string_view>(strconcat(
                         string_view(""), sep1, usedtext->sensorends, sep2))
                   : string_view("", 0),
        nu < etime ? string_view(ends, appcurve.datestr(etime, ends))
                   : string_view("", 0),
        sep1, usedtext->sensorexpectedend, sep2,
        string_view(pends, appcurve.datestr(reallends, pends)), endstr);
  }
#ifndef DONTTALK
  void speak() {
    LOGGER("speak %s\n", text.data());
    ::speak(text.data());
  }
#endif
};

float JCurve::getfreey() {
  const int nrlabs = settings->getlabelcount();

  static const int mid = nrlabs / 2 - 1;
  static const float midh = numtypeheight(mid);
  static float boven = numtypeheight(mid + 1);
  return (boven + midh) / 2.0f;
}
int JCurve::typeatheight(const float h) {
  //    const float gr= density*24;
  const float gr = density * 24;
  const int maxl = settings->getlabelcount();
  for (int i = 0; i < maxl; i++) {
    if (numheights[i] >= 0) {
      float th = numtypeheight(i);
      if (fabsf(h - th) < gr)
        return i;
    }
  }
  return -1;
}

#define makeshows(x)                                                           \
  void setshow##x(int val) { settings->data()->show##x = val; }                \
  int getshow##x() { return settings->data()->show##x; }
makeshows(calibrated) makeshows(scans) makeshows(stream);
makeshows(meals) makeshows(numbers) makeshows(histories)
#ifndef WEAROS
    extern bool showpers;
extern void showpercentiles(NVGcontext *avg);
#endif
int getalarmcode(const uint32_t glval, float drate, SensorGlucoseData *hist);
void processglucosevalue(int sendindex, int newstart);
struct {
  float left, top, right, bottom;
} menupos, hidepos;
#ifndef NOLOG
void logmenupos() {
  LOGGER("left=%.1f top=%.1f right=%.1f bottom=%.1f\n", menupos.left,
         menupos.top, menupos.right, menupos.bottom);
}
#else
#define logmenupos()
#endif
extern std::vector<int> usedsensors;

const float JCurve::getsetlen(NVGcontext *avg, float x, float y,
                              const char *set, const char *setend,
                              bounds_t &bounds) {
  nvgTextBounds(avg, x, y, set, setend, bounds.array);
  return bounds.xmax - bounds.xmin;
}
inline int64_t menuel(int menu, int item) { return menu + item * 0x10LL; }

#define arsizer(x) sizeof(x) / sizeof(x[0])

#ifdef WEAROS

const int *menuopt0[] = {nullptr, nullptr, nullptr, nullptr, nullptr};
const int **optionsmenu[] = {menuopt0, nullptr};
constexpr const int menulen[] = {arsizer(jugglucotext::menustr0),
                                 arsizer(jugglucotext::menustr2)};
int getmenulen(const int menu) {
  int len = menulen[menu];
  //    if(menu==1&&settings->staticnum()) return len-1;
  if (!menu && !alarmongoing)
    return len - 1;

  return len;
}

void setfloatptr() {}
#else
int menus = 0;
const int *menuopt0[] = {&showui, &menus,  nullptr, nullptr,
                         nullptr, nullptr, nullptr, nullptr};

const int *menuopt0b[] = {nullptr, nullptr, nullptr, nullptr,
                          nullptr, nullptr, nullptr};
const int *menuopt1[] = {&appcurve.showcalibrated, &appcurve.showscans,
                         &appcurve.showstream,     &appcurve.showhistories,
                         &appcurve.shownumbers,    &appcurve.showmeals,
                         &appcurve.invertcolors};
const int **optionsmenu[] = {menuopt0, menuopt0b, menuopt1, nullptr};
constexpr const int menulen[] = {
    arsizer(jugglucotext::menustr0), arsizer(jugglucotext::menustr1),
    arsizer(jugglucotext::menustr2), arsizer(jugglucotext::menustr3)};
int getmenulen(const int menu) {
  int len = menulen[menu];
  if (!menu && !alarmongoing)
    return len - 1;
  return len;
}
void setfloatptr() { menuopt0b[6] = &settings->data()->floatglucose; }

// void setnewamount() { }
#endif

constexpr const int maxmenulen =
    *std::max_element(std::begin(menulen), std::end(menulen));
constexpr int maxmenu = arsizer(jugglucotext::menustr);
#ifdef WEAROS
static_assert(maxmenu == 2);
#else
static_assert(maxmenu == 4);
#endif

int getmenu(int tapx, float dwidth) { return tapx * maxmenu / dwidth; }
#ifdef WEAROS
int64_t JCurve::doehier(int menu, int item) {
  switch (menu) {
  case 0:
    switch (item) {
    case 0:
      nrmenu = 0;
      return 1LL * 0x10 + 1;
    case 1:
      nrmenu = 0;
      return 3LL * 0x10;
    case 2: // invertcolors=!invertcolors; setinvertcolors(invertcolors) ;
            // return -1LL; break;

      nrmenu = 0;
      return menuel(0, 2);
    case 3:
      nrmenu = 0;
      return 4LL * 0x10;
    case 4:
      nrmenu = 0;
      return menuel(0, 7);
    };
  case 1:
    nrmenu = 0;
    switch (item) {
    case 0:
      return 2LL * 0x10 + 3;
      //                case 1: return 1LL*0x10+3;
    case 1: {
      auto max = time(nullptr);
      setstarttime(max - duration * 3 / 5);
      return -1LL;
    }
    case 2:
      prevdays(1);
      return -1LL;
    case 3:
      //                    if(settings->staticnum()) return -1LL;
      return menuel(1, 2);
    };
  }
  return -1LL;
}
#else
int64_t JCurve::doehier(int menu, int item) {
  switch (menu) {
  case 0:
    switch (item) {
    case 0:
      showui = !showui;
      settings->setui(showui);
      break;
    default:
      nrmenu = 0;
      break;
    };
  case 1:
    switch (item) {
      //                case 0: notify=!notify;return
      //                menu|item*0x10|(notify<<8);
    case 2:
      nrmenu = 0;
      //                    if(settings->staticnum()) return -1LL;
      break;
    case 3:
      nrmenu = 0;
      if (!numlist) {
        numiterinit();
        numlist = 1;
      }
      break;

#ifdef PERCENTILES
    case 4:
      nrmenu = 0;
      if (!makepercetages())
        return -1LL;
      ;
      break;
#endif
    case 6:
      break;
    default:
      nrmenu = 0;
      break;
    };
  case 2:
    switch (item) {
      /*
          case 0: {
              nrmenu=0;
              int lastsensor=sensors->lastscanned();
              if(lastsensor>=0) {
                  const SensorGlucoseData
         *hist=sensors->getSensorData(lastsensor); if(hist) { const ScanData
         *scan= hist->lastscan(); const uint32_t nu= time(nullptr);
                      if(scan&&scan->valid()&&((nu-scan->t)<(60*60*5)))
                          scantoshow={lastsensor,scan,nu};
                      }
                  }
              }; return -1ll; */
    case 0:
      showcalibrated = !showcalibrated;
      setshowcalibrated(showcalibrated);
      return -1ll;
    case 1:
      showscans = !showscans;
      setshowscans(showscans);
      return -1ll;
    case 2:
      showstream = !showstream;
      setshowstream(showstream);
      return -1ll;
    case 3:
      showhistories = !showhistories;
      setshowhistories(showhistories);
      return -1ll;
    case 4:
      shownumbers = !shownumbers;
      setshownumbers(shownumbers);
      return -1ll;
    case 5:
      showmeals = !showmeals;
      setshowmeals(showmeals);
      return -1ll;

    case 6:
      invertcolors = !invertcolors;
      setinvertcolors(invertcolors);
      return menu + invertcolors * 0x10;
      break; // return -1ll;
    };
    break;
  case 3: {
    nrmenu = 0;
    switch (item) {
    case 0: {
      auto max = time(nullptr);
      //    starttime=starttimefromtime(max);
      //            if((starttime+duration)<max)
      setstarttime(max - duration * 3 / 5);
      return -1;
    };
    case 3:
      prevdays(1);
      return -1;
    case 4:
      nextdays(1);
      return -1;
    case 5:
      prevdays(7);
      return -1;
    case 6:
      nextdays(7);
      return -1;
    default:
      break;
    };
  };

  default:
    nrmenu = 0;
  }
  return menu + item * 0x10;
}
#endif
void JCurve::withredisplay(NVGcontext *avg, uint32_t nu) {
  startstep(avg, *getwhite());
#ifndef WEAROS
  if (showpers) {
    showpercentiles(avg);
  } else
#endif
  {

#ifdef WEAROSx
    int oldtapx = tapx;
    tapx = -8000;
#endif

    if (!displaycurve(avg, nu) &&
        (((tapx
#ifdef WEAROSx

           = oldtapx
#endif
           ) >= 0 &&
          !selshown && (selmenu = getmenu(tapx, dwidth), true)) ||
         nrmenu)) {
      showtext(avg, nu, selmenu);
    }
  }
  tapx = -8000;

  LOGAR("end withredisplay");
}

#include "secs.h"
void JCurve::prevdays(int nr) {
  // starttime=starttimefromtime(starttime-nr*daysecs);
  setstarttime(starttime - nr * daysecs);
  auto minstart = minstarttime();
  if (starttime < minstart)
    setstarttime(minstart);
}
void JCurve::nextdays(int nr) {
  // starttime=starttimefromtime(starttime+daysecs*nr);
  setstarttime(starttime + daysecs * nr);
#ifndef WEAROS
  if (!showpers)
#endif
  {
    auto maxstart = maxstarttime();
    if (starttime > maxstart)
      setstarttime(maxstart);
  }
}

// int notify=false;

#ifdef USE_DISPLAYER
bool showtextbox(JCurve *j, NVGcontext *avg) {
  if (displayer) {
    j->textbox(avg, "", displayer->text);
    return true;
  }
  return false;
}
#endif
#ifndef DONTTALK
static bool speakmenutap(float x, float y) {
  if (x < menupos.left || x >= menupos.right) {
    return false;
  }
  float dist = (menupos.bottom - menupos.top) / nrmenu;
  int item = (y - menupos.top) / dist;
  if (item >= 0 && item < nrmenu) {
    LOGGER("menuitem selmenu=%d item=%d\n", selmenu, item);
    auto options = optionsmenu[selmenu];
    const auto label = usedtext->menustr[selmenu][item];
    if (!options || !options[item])
      speak(label.data());
    else {
      constexpr const int maxbuf = 256;
      char buf[maxbuf];
      memcpy(buf, label.data(), label.size());
      char *ptr = buf + label.size();
      *ptr++ = '\n';
      if (*options[item])
        strcpy(ptr, usedtext->checked);
      else
        strcpy(ptr, usedtext->unchecked);
      speak(buf);
    }
    return true;
  }
  return false;
}
#endif
void setnowmenu(time_t nu);
void JCurve::showtext(NVGcontext *avg, time_t nu, int menu) {
  LOGAR("showtext");
#ifdef WEAROS
  if (menu == 1) {
    setnowmenu(nu);
    //        setnewamount();
  }
#else
  if (menu == 3)
    setnowmenu(nu);
//    if(menu==1) setnewamount();
#endif
  const string_view *menuitem = usedtext->menustr[menu];
  nrmenu = getmenulen(menu);
  constexpr const float randsize =
#ifdef WEAROS
      10
#else
      16
#endif
      ;
  float xrand = randsize * density;
  float yrand = randsize * density;
  //    float menutextheight=density*48;
  float menuplace = dwidth / maxmenu;
  float x = xrand + menu * menuplace, starty = yrand + statusbarheight,
        y = starty;

  bool portrait = false;
#ifndef WEAROS
  if (height > width)
    portrait = true;
#endif
  if (portrait) {
    starty = dtop + dheight + yrand; // Bottom third
    y = starty;
    // Adjust x if needed, but menuplace calculation uses dwidth.
    // If dwidth is full width in portrait, it should be fine.
    // dwidth in portrait is full width (resizescreen sets dwidth=width).
  }

  nvgTextAlign(avg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);

  bounds_t bounds;

  nvgFontFaceId(avg, menufont);
  nvgFontSize(avg, menusize);
  nvgTextBounds(avg, x, y, menuitem[0].data(),
                menuitem[0].data() + menuitem[0].size(), bounds.array);
  //    nvgText(avg, x,y, menuitem[0].data(),
  //    menuitem[0].data()+menuitem[0].size());
  float maxx = bounds.xmax;
  float maxwidth = bounds.xmax - bounds.xmin;
  for (int i = 1; i < nrmenu; i++) {
    y += menutextheight;
    nvgTextBounds(avg, x, y, menuitem[i].data(),
                  menuitem[i].data() + menuitem[i].size(), bounds.array);
    if (maxx < bounds.xmax)
      maxx = bounds.xmax;
    float maxwidthone = bounds.xmax - bounds.xmin;
    if (maxwidthone > maxwidth)
      maxwidth = maxwidthone;
  }
  float height = y + bounds.ymax - bounds.ymin;
  nvgBeginPath(avg);
  nvgFillColor(avg, *getmenucolor());
  //     nvgFillColor(avg, white);
  float mwidth = maxx - x + 2 * xrand;
  //     float minmenu=128*density;
  float minmenu =
#ifdef WEAROS
      80
#else
      128
#endif

      * density;
  float maxmenu = 280 * density;
  if (mwidth < minmenu)
    mwidth = minmenu;
  else if (mwidth > maxmenu)
    mwidth = maxmenu;
  x += (menuplace - mwidth) / 2;
#ifdef WEAROS
  if (menu == 0)
    x += xrand;
#endif
  menupos = {x - xrand, starty - yrand, x - xrand + mwidth,
             height - starty + 2 * yrand + starty -
                 yrand}; // Fixed height calculation
  // Original: height-starty+2*yrand+starty-yrand = height + yrand.
  // height is absolute y coordinate of bottom. starty is absolute top.
  // Box top is starty-yrand.
  // Box height should cover up to height + yrand (padding).
  // menupos.bottom = height + yrand ?
  // Wait, 'height' variable above was calculated as last y + text height.
  // So yes, menupos.bottom should be around there.

  logmenupos();
  nvgRect(avg, x - xrand, starty - yrand, mwidth, height - starty + 2 * yrand);
  nvgFill(avg);
#ifdef WEAROS
  if (menu == 0) {
    nvgTextAlign(avg, NVG_ALIGN_RIGHT | NVG_ALIGN_TOP);
    x += maxwidth;
  }
#endif
  y = starty;
  //     nvgFillColor(avg, *getwhite());
  nvgFillColor(avg, *getmenuforegroundcolor());
  //     nvgFillColor(avg, black);
  for (int i = 0; i < nrmenu; i++) {
    nvgText(avg, x, y, menuitem[i].data(),
            menuitem[i].data() + menuitem[i].size());
    y += menutextheight;
  }

  if (const int **options = optionsmenu[menu]) {
    y = starty;
    const char set[] = "[x]";
    const char unset[] = "[ ]";
    constexpr int len = 3;
    float xpos;
#ifdef WEAROS
    if (menu == 0) {
      xpos = x;
    } else
#endif
    {
      static const float dlen = getsetlen(avg, x, y, set, set + len, bounds);
      xpos = x - 2 * xrand + mwidth - dlen;
    }
    for (int i = 0; i < nrmenu; i++) {
      if (const int *optr = options[i]) {
        const char *op = *optr ? set : unset;
        nvgText(avg, xpos, y, op, op + len);
      }
      y += menutextheight;
    }
  }

  LOGAR("end showtext");
}

void JCurve::withbottom() {
  extern const int maxmenulen;
  dheight = height - dtop - dbottom;
  const float maxmenu = (float)dheight / maxmenulen;
  if (menutextheight > maxmenu)
    menutextheight = maxmenu;
  LOGGER("menutextheight=%f\n", menutextheight);
}
void JCurve::resizescreen(int widthin, int heightin, int initscreenwidth) {
  width = widthin;
  height = heightin;
  LOGGER("resize(%d,%d)\n", width, height);

  bool portrait = false;
#ifndef WEAROS
  if (height > width)
    portrait = true;
#endif

  if (portrait) { // Portrait mode
    float third = height / 3.0f;
    dleft = 0;
    dright = 0;
    dtop = third;    // Top third reserved for "left panel" (current glucose)
    dbottom = third; // Bottom third reserved for menus
    dwidth = width;
    dheight = third;                 // Middle third for graph
  } else {                           // Landscape or Watch
    dwidth = width - dleft - dright; // Display area for graph in pixels
  }

  textheight = density * 48;
  int times = ceil(height / textheight);
  textheight = height / times;
  menutextheight = density * 48;

  withbottom();

  float facetimelen = 2.0f * dwidth / 3.0f;
  LOGGER("facetimelen=%.1f\n", facetimelen);
  facetimefontsize = smallsize * facetimelen / timelen;
  LOGGER("facetimefontsize=%.1f\n", facetimefontsize);
  float straal = dwidth * 0.5f;
  facetimey =
      (straal - sqrt(pow(straal, 2.0) - pow(facetimelen * .5, 2.0))) * .70;

  LOGGER("facetimey=%.1f\n", facetimey);
}

int getglucosestr(double nonconvert, char *glucosestr, int maxglucosestr,
                  int glucosehighest) {
  if (nonconvert < glucoselowest) {
    return appcurve.mkshowlow(glucosestr, maxglucosestr);
  } else {
    if (nonconvert > glucosehighest) {
      return appcurve.mkshowhigh(glucosestr, maxglucosestr, glucosehighest);
    } else {
      const float convglucose = gconvert(nonconvert * 10);
      return snprintf(glucosestr, maxglucosestr, gformat, convglucose);
    }
  }
}

void JCurve::showscanner(NVGcontext *avg, const SensorGlucoseData *hist,
                         int scanident, time_t nu) {
  const ScanData &last = *hist->getscan(scanident);
  const bool isold = (nu - last.t) >= maxbluetoothage;
  startstep(avg, isold ? getoldcolor() : *getwhite());
  float right = dleft + dwidth - statusbarright;
  float x = right;
  constexpr int maxbuf = 50;
  char buf[maxbuf * 2];
  time_t tim = last.t;
  struct tm tmbuf;
  struct tm *tms = localtime_r(&tim, &tmbuf);
  //    int len=snprintf(buf,maxbuf,"%02d:%02d ", tms->tm_hour,mktmmin(tms));
  int len = mktime(tms->tm_hour, mktmmin(tms), buf);
  buf[len++] = ' ';
  char *buf1 = buf + len;
  --len;
  const int32_t gluval = last.g;
  int len1;
  float endtime = x, sensleft = 0.0f;

  if (gluval < glucoselowest) {
    len1 = mkshowlow(buf1, maxbuf);
    endtime -= smallerlen;
    sensleft = smallerlen;
  } else {
    int glucosehighest = hist->getmaxmgdL();
    if (gluval > glucosehighest) {
      len1 = mkshowhigh(buf1, maxbuf, glucosehighest);
      endtime -= smallerlen;
    } else {
      len1 = snprintf(buf1, maxbuf, gformat, gconvert(gluval * 10.0f));
    }
  }

  float bounds[4];
  nvgTextAlign(avg, NVG_ALIGN_RIGHT | NVG_ALIGN_MIDDLE);
  nvgFontSize(avg, headsize);
  nvgTextBounds(avg, x, dtop + dheight / 2, buf1, buf1 + len1, bounds);
  nvgFillColor(avg, *getblack());
  auto [first, y] = drawtrender(avg, hist->gettrendsbuf(scanident), dleft, dtop,
                                bounds[0] - dleft, dheight);
  float th = (bounds[3] - bounds[1]) / 2.0;
  if (y < th)
    y = th;
  else if ((dheight - (y - dtop)) < th)
    y = dheight - th;

  nvgText(avg, x, y, buf1, buf1 + len1);
  const bool showabove = y > (dheight / 2);
  const float yunder = y + (showabove ? -1 : 1) * headsize / 2.0;
  //    nvgFontSize(avg,smallsize );
  nvgFontSize(avg, headsize * .134f);
  nvgText(avg, endtime, yunder, buf, buf + len);
  const auto sensorname = hist->othershortsensorname();
  nvgTextAlign(avg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
  const auto sensorx = bounds[0] - sensleft;
  nvgText(avg, sensorx, yunder, sensorname.begin(), sensorname.end());
  const bool showdate = (nu - last.t) >= 60 * 60 * 12;
  constexpr const int maxdatebuf = 30;
  int datelen;
  char datebuf[maxdatebuf];
  if (isold) {
    if (showdate) {
      float datey = yunder + (showabove ? -1 : 1) * sensorbounds.height;
      nvgTextAlign(avg, NVG_ALIGN_RIGHT | NVG_ALIGN_MIDDLE);
      datelen =
          snprintf(datebuf, maxdatebuf, "%s %d %s %04d",
                   usedtext->speakdaylabel[tmbuf.tm_wday], tmbuf.tm_mday,
                   usedtext->monthlabel[tmbuf.tm_mon], 1900 + tmbuf.tm_year);
      nvgText(avg, right, datey, datebuf, datebuf + datelen);
    }
    nvgStrokeWidth(avg, TrendStrokeWidth);
    nvgStrokeColor(avg, *getwhite());
    nvgBeginPath(avg);
    nvgMoveTo(avg, dleft, dtop);
    nvgLineTo(avg, right, dheight);
    nvgStroke(avg);
    nvgBeginPath(avg);
    nvgMoveTo(avg, right, dtop);
    nvgLineTo(avg, dleft, dheight);
    nvgStroke(avg);
  }
#ifndef DONTTALK

  if (settings->data()->speakmessagesget()) {
    char value[300];
    char *ptr = value;
    ;
    if (isold) {
      if (showdate) {
        memcpy(ptr, datebuf, datelen);
        ptr += datelen;
        *ptr++ = '\n';
        *ptr++ = '\n';
      }
      memcpy(ptr, buf, len);
      ptr += len;
      *ptr++ = '\n';
    }
    //        auto trend=usedtext->trends[last.tr];
    const auto trend = usedtext->getTrendName(last.tr);
    memcpy(ptr, trend.data(), trend.size());
    ptr += trend.size();
    *ptr++ = '\n';
    *ptr++ = '\n';
    memcpy(ptr, buf1, len1 + 1);
    speak(value);
  }
#endif

  showok(avg, (last.g > 70 && last.g <= 140),
         ((y - dtop) < (dheight / 2)) ? false : true);
}

int JCurve::showerrorvalue(NVGcontext *avg, const SensorGlucoseData *sens,
                           const time_t nu, float getx, float gety, int index) {

  shownglucose[index].glucosevalue = 0;
  getx -= headsize / 3;
  shownglucose[index].glucosevaluex = getx;
  shownglucose[index].glucosevaluey = gety + headsize * .5;
  nvgTextAlign(avg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
  nvgFontSize(avg, headsize / 6);
  if (settings->data()->nobluetooth) {
    LOGAR("nobluetooth");
    if (hasnetwork()) {
      return 1;
    } else {
      return 2;
    }
  } else {
    LOGAR("!nobluetooth");
    {
      if (!bluetoothEnabled()) {
        LOGAR("bluetooth not Enabled");
        return 3;
      } else {
        if ((nu - sens->receivehistory) < 60) {
          static char buf[256];
          const auto past = usedtext->receivingpastvalues;
          memcpy(buf, past.data(), past.size());
          char *ptr = buf + past.size();
          ptr += sprintf(ptr, ": %d", sens->pollcount());
          nvgTextBox(avg, getx, gety, getboxwidth(getx), buf, ptr);
          shownglucose[index].errortext = buf;
        } else {

          if (sens->hasSensorError(nu)) {
            const std::string_view sensorerror = sens->replacesensor
                                                     ? usedtext->streplacesensor
                                                     : usedtext->stsensorerror;
            char buf[sensorerror.size() + 17];
            int senslen = sens->showsensorname().size();
            memcpy(buf, sens->showsensorname().data(), senslen);
            memcpy(buf + senslen, sensorerror.data(), sensorerror.size());
            auto boxwidth = getboxwidth(getx);
            nvgTextBox(avg, getx, gety, boxwidth, buf,
                       buf + sensorerror.size() + senslen);
            shownglucose[index].errortext = sensorerror.data();
          } else {
            int state = sens->getinfo()->patchState;
            if (state && state != 4) {
              const auto format =
                  state > 4 ? usedtext->endedformat : usedtext->notreadyformat;
              static char buf[256];
              int len = snprintf(buf, sizeof(buf) - 1, format.data(),
                                 sens->showsensorname().data(), state);
              nvgTextBox(avg, getx, gety, getboxwidth(getx), buf, buf + len);
              shownglucose[index].errortext = buf;
            } else {
              char buf[usedtext->noconnectionerror.size() + 17];
              int senslen = sens->showsensorname().size();
              memcpy(buf, sens->showsensorname().data(), senslen);
              memcpy(buf + senslen, usedtext->noconnectionerror.data(),
                     usedtext->noconnectionerror.size());
              nvgTextBox(avg, getx, gety, getboxwidth(getx), buf,
                         buf + usedtext->noconnectionerror.size() + senslen);
              shownglucose[index].errortext =
                  usedtext->noconnectionerror.data();
            }
          }
        }
        return 0;
      }
    }
  }
}

void JCurve::startstep(NVGcontext *avg, const NVGcolor &col) {
  glViewport(0, 0, width, height);
  glClearColor(col.r, col.g, col.b, col.a);
  glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
  glEnable(GL_BLEND);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  glEnable(GL_CULL_FACE);
  glDisable(GL_DEPTH_TEST);
  startstepNVG(avg, width, height);
}

void JCurve::endstep(NVGcontext *avg) {
  nvgEndFrame(avg);
  glEnable(GL_DEPTH_TEST);
}

time_t lastviewtime = 0;
int JCurve::onestep(NVGcontext *avg) {
  if (!avg)
    return 0;
  LOGAR("onestep");
  time_t nu = time(nullptr);
  lastviewtime = nu;
  updateusedsensors(nu);

  selshown = false;
  int ret = 0;
  emptytap = false;
#ifdef JUGGLUCO_APP
  if (showoldscan(avg, nu) > 0) {
    ret = 1;
  } else
#endif
  {

#if defined(JUGGLUCO_APP) && !defined(WEAROS)
    if (numlist) {
      shownumlist(avg);
    } else
#endif

    {
      withredisplay(avg, nu);
    }
  }
#if defined(JUGGLUCO_APP) && defined(USE_DISPLAYER)
  extern bool showtextbox(JCurve *, NVGcontext * avg);
  if (showtextbox(this, avg))
    ret = 1;
#endif
  endstep(avg);
  return ret;
}

AppCurve appcurve;

// Stubs for missing symbols to satisfy linker
int JCurve::badscanMessage(NVGcontext *avg, int kind) { return 0; }

extern NVGcontext *genVG;

void initopengl(float small, float menu, float density, float headin) {
  if (!genVG) {
    genVG = nvgCreateGLES2(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
  }
  appcurve.setfontsize(small, menu, density, headin);
  appcurve.initfont(genVG);
}

void JCurve::flingX(float vol) {}
int JCurve::translate(float dx, float dy, float yold, float y) { return 0; }
void JCurve::xscaleGesture(float scalex, float midx) {}
void JCurve::prevscr() {}
void JCurve::nextscr() {}
void pressedback() {}
bool isbutton(float x, float y) { return false; }
int64_t JCurve::screentap(float x, float y) { return 0; }
int64_t JCurve::longpress(float x, float y) { return 0; }
int hitremove(int64_t ptr) { return 0; }
bool openglstarted = false;
void numfirstpage() {}
void JCurve::numpagenum(uint32_t tim) {}
void JCurve::endnumlist() {}
void JCurve::numiterinit() {}
void JCurve::showok(NVGcontext *avg, bool good, bool up) {}
void setnowmenu(time_t nu) {}
void JCurve::shownumlist(NVGcontext *vg) {}
void JCurve::showHideButton(NVGcontext *avg) {}
strconcat getsensortext(const SensorGlucoseData *hist) {
  if (!hist) {
    return strconcat(std::string_view(""), std::string_view("No sensor data"));
  }

  const time_t nu = time(nullptr);
  const time_t recv_hist = hist->receivehistory;
  const long diff = nu - recv_hist;

  // Check if actively receiving history (within last 60 seconds)
  if (diff < 60) {
    if (!usedtext) {
      return strconcat(std::string_view(""), std::string_view("Receiving"));
    }

    static char buf[256];
    const auto past = usedtext->receivingpastvalues;
    // Format: "Receiving old values: 123"
    int len = snprintf(buf, sizeof(buf), "%.*s: %d", (int)past.size(),
                       past.data(), hist->pollcount());

    return strconcat(std::string_view(""), std::string_view(buf, len));
  }

  // Check for sensor errors
  if (hist->hasSensorError(nu)) {
    return strconcat(std::string_view(""), std::string_view("Sensor Error"));
  }

  // Check connection status
  auto *info = hist->getinfo();
  if (!info) {
    return strconcat(std::string_view(""), std::string_view(""));
  }
  int state = info->patchState;
  if (state == 0 || state == 4) {
    return strconcat(std::string_view(""), std::string_view(""));
  } else if (state > 4) {
    return strconcat(std::string_view(""), std::string_view("Sensor Ended"));
  } else {
    return strconcat(std::string_view(""), std::string_view("Warming Up"));
  }
}
int nrcolumns = 0;
void numpageforward() {}
void numpagepast() {}

void processglucosevalue(int sendindex, int newstart) {
  if (!sensors)
    return;
  LOGGER("processglucosevalue %d %d\n", sendindex, newstart);
  if (SensorGlucoseData *hist = sensors->getSensorData(sendindex)) {
    if (newstart >= 0) {
      LOGGER("newstart=%d\n", newstart);
      hist->backstream(newstart);
    }
    if (const ScanData *poll = hist->lastValidStream()) {
      const time_t nutime = time(nullptr);
      const time_t tim = poll->t;
      const int dif = nutime - tim;
      if (dif < maxbluetoothage) {
        sensor *senso = sensors->getsensor(sendindex);
        logprint("finished=%d not finished %s ", senso->finished, ctime(&tim));
        if (senso->finished && senso->reactivateFromData()) {
          backup->resensordata(sendindex);
        }

      } else {
        logprint(" too old %s \n", ctime(&tim));
        logprint("dist=%d, dif=%d nu %s", maxbluetoothage, dif, ctime(&nutime));
      }
    } else {
      logprint("no stream data\n");
    }
  } else {
    logprint("no sensor\n");
  }
}
