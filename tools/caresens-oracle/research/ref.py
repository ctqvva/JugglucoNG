from lab import *
from cur import loess,mm,tm,smk,gm,mins
TRSPAN=35
SMG=lambda s:s.St[9]-s.St[0]<3000
TRDEN=lambda d:d/60
KS=np.array([-1.6,2.7,6.6,5.6,5.4,3.9,-1.6])/21
E5=math.exp(-.5)
class Ref:
    def __init__(s,d):
        f=lambda n:float(d[n][0]); s.slope=f('slope100');s.vref=f('vref');s.eapp=f('eapp'); s.n=0;s.cum=0
        s.seqp=0;s.tp=0;s.tbuf=[];s.K=None;s.w=0;s.S=np.zeros(10);s.St=np.zeros(10,dtype=np.int64);s.Sq=np.zeros(10,dtype=int)
        s.dn=0;s.dmean=0.;s.dM2=0.;s.dhi=0;s.dlo=0;s.level=6;s.glast=None;s.lvdone=False;s.isum=0.;s.icnt=0;s.hx=None;s.hy=0.;s.msum=0.;s.mcnt=0;s.roc=[math.nan]*4;s.fl=[0]*6;s.kst=0;s.kfl=0;s.zp=0.
        s.lot=2 if f('eapp')<.075 else 1
    def valid(s): return 2.5<=s.slope<=8 and 1.4<=s.vref<1.6 and .0386<=s.eapp<.113
    def clip(s,x,contig):
        """contig: number of prior samples usable"""
        raw=list(s.raw);cl=list(s.cl);fl=list(s.fl)
        for j,v in enumerate(x):
            if j<5 and not contig: raw.append(v);cl.append(v);fl.append(0);continue
            h=cl if sum(1 for q in fl[-6:] if q)>=4 else raw
            z=np.array(h[-5:]+[v]); md=np.median(z); sg=1.486*np.median(abs(z-md))
            if sg==0: sg=1.253314*np.mean(abs(z-np.mean(z)))
            if abs(v-md)>1.5*sg: n=cl[-1]+math.copysign(sg,v-md); f=1 if v>md else -1
            else: n=v;f=0
            raw.append(v);cl.append(n);fl.append(f)
        s.raw,s.cl,s.fl=raw[-5:],cl[-5:],fl[-6:]
        return np.array(cl[5:])
    def step(s,seq,t,wo,temp):
        o={}
        x=(wo.astype(float)*s.vref/40950-s.eapp)*100
        first=s.n==0; consec=(not first) and seq==s.seqp+1
        ts=t-290+10*np.arange(30)
        if not consec:
            q=x.copy(); s.raw=list(x[-5:]);s.cl=list(x[-5:]);s.fl=[0]*6
        else: q=s.clip(x,ts[0]-s.t60[-5]<58)
        if first: y=q;sm=False
        else:
            t90=np.append(s.t60,ts);y90=np.append(s.q60,q)
            y=q
            if s.n>=2 and t90[89]-t90[0]<898: y=loess(y90)[60:]
            sm=ts[0]-s.t60[-1]<18
        m=gm(y); one=mins(smk(m,s.p3)) if sm else mins(m); s.p3=m[-3:]
        s.t60=np.append(s.t60[30:],ts) if not first else np.append(np.zeros(30,dtype=np.int64)-10**9,ts)
        s.q60=np.append(s.q60[30:],q) if not first else np.append(np.zeros(30),q)
        c5=tm(one); corr=c5-(.7 if s.lot==1 else .243)
        # sensitivity-loss compensation (high-pass + 4-day reference)
        if 5<=seq<=24: s.isum+=corr;s.icnt+=1
        xi=corr
        if seq>=24 and s.icnt:
            if s.hx is None: s.hx=s.isum/s.icnt
            s.hy=.99891*(corr-s.hx)+.99782*s.hy; s.hx=corr
        if 865<=seq<=2016: s.msum+=corr;s.mcnt+=1
        if 865<=seq<=2016 and s.glast is not None and 40<=s.glast<=600:
            v=s.glast; s.dn+=1; d_=v-s.dmean; s.dmean+=d_/s.dn; s.dM2+=d_*(v-s.dmean); s.dhi+=v>180; s.dlo+=v<70
        if seq==2017 or (seq>2017 and s.level==6 and not s.lvdone):
            s.lvdone=True
            if s.dn>1:
                tar=100*s.dhi/s.dn; tbr=100*s.dlo/s.dn; cv=100*math.sqrt(s.dM2/(s.dn-1))/s.dmean
                s.level=6 if (tbr>=5 or cv>=36) else 5 if tar>=60 else 4 if tar>=30 else 3 if tar>=15 else 1 if (tar<5 and cv<20) else 2
        fac={1:1.,2:.95,3:.85,4:.8,5:.75}.get(s.level)
        if seq>2016 and s.mcnt and fac:
            ref=fac*s.msum/s.mcnt; lp=corr-s.hy
            if lp<ref: xi=corr+min(1.,(seq-2016)/288)*(ref-lp)
        corr=xi
        dr=min(1.0,0.92319967+seq*(4.76441685e-05+seq*(5.39473347e-09-seq*4.63640418e-12))) if seq<1829 else 1.
        od=corr/dr
        s.tbuf=([temp]+s.tbuf)[:4]; tl=sum(s.tbuf)/len(s.tbuf); srt=min(1.134,max(.882,.0328*tl-.118))
        cg=od*100/s.slope/srt
        res=cg
        if cg<180: res+=30*(1-cg/180)*(1-cg/385)*(1 if seq<=288 else max(0,(432-seq)/144))
        dt=t-s.tp
        if first or seq<10 or dt>1500: s.K=np.array([res,res,0.]);s.w=1;ad=res;s.roc=[math.nan]+s.roc[:3];s.kst=0;s.kfl=0
        else:
            dz=res-s.zp; rc=dz/(dt/60) if dt else (math.nan if dz==0 else math.copysign(math.inf,dz)); prev=s.roc[:3]; s.roc=[rc]+prev
            if s.kst==3:
                s.kfl=1 if abs(rc)>3 else s.kfl+1
                init=True
                if s.kfl>=6: s.kfl=0;s.kst=0
            else:
                m=sum(prev)/3; init=abs(rc)>5 and not (m*math.copysign(1,rc)>1)
                if init: s.kst=3;s.kfl=1
            if init: s.K=np.array([res,res,0.]);s.w=1;ad=res
            else:
                p_=np.array([E5*s.K[0]+(1-E5)*s.K[1],s.K[1]+s.K[2],s.K[2]]); s.K=p_+np.array([.6729,1.761,.1279])*(res-p_[0]); s.w+=1
                al=min(1,(s.w-1)/24); ad=al*s.K[1]+(1-al)*res
        s.zp=res
        s.S=np.append(s.S[1:],ad);s.St=np.append(s.St[1:],t);s.Sq=np.append(s.Sq[1:],seq)
        if s.n>=9 and SMG(s):
            xx=np.append(s.S,[ad]*3); b=s.S.copy()
            for i in range(3,9): b[i]=(KS*xx[i-3:i+4]).sum()
            s.S=b
        tr=100.
        if s.n>=11 and s.St[3]>0 and TRDEN(s.St[9]-s.St[3])<=TRSPAN and not ((s.S[3:]<40).any() or (s.S[3:]>500).any()):
            tr=(s.S[9]-s.S[3])/TRDEN(s.St[9]-s.St[3]); d9=s.S[9]-s.S[8]; d8=s.S[8]-s.S[7]
            if abs(tr)>1 and np.sign(d9)!=np.sign(tr): tr=d9/((s.St[9]-s.St[8])/60) if np.sign(d8)!=np.sign(tr) else 0.
        s.seqp,s.tp=seq,t;s.n+=1
        s.glast=ad
        return dict(one=one,c5=c5,corr=corr,od=od,srt=srt,cg=cg,res=res,ad=ad,S=s.S.copy(),tr=tr)
def compare(nm,wo,T=34.,d=None,seq=None,t=None,verbose=1):
    d=dev() if d is None else d; N=len(wo); sq=np.arange(1,N+1) if seq is None else np.asarray(seq); t=BASE+300*sq if t is None else np.asarray(t); T=np.broadcast_to(T,(N,))
    r,o,g,a,s=run(d,wo,T,seq=sq,t=t,snap=True); R=Ref(d); first={}; mx={}
    for i in range(N):
        if o['data_type'][i] in (1,2): continue
        m=R.step(int(sq[i]),int(t[i]),wo[i],float(T[i]))
        chk=dict(one=(m['one'],g['tran_inA_1min'][i]),od=(m['od'],g['out_drift'][i]),cg=(m['cg'],g['init_cg'][i]),res=(m['res'],g['out_rescale'][i]),ad=(m['ad'],g['opcal_ad'][i]),S=(m['S'],s['smooth_sig_in'][i]),tr=(m['tr'],o['trendrate'][i]),glu=(m['ad'],o['result_glucose'][i]),lvl=(R.level,s['diabetes_level_diabetes'][i]),hi=(R.dhi,s['diabetes_cnt_hi_glu'][i]),lo=(R.dlo,s['diabetes_cnt_low_glu'][i]),dmean=(R.dmean,s['diabetes_mean_x'][i]))
        for k,(x,y) in chk.items():
            e=np.abs(np.asarray(x)-np.asarray(y)).max(); mx[k]=max(mx.get(k,0),e)
            if e>1e-7 and k not in first: first[k]=i+1
    print(nm,{k:float('%.1e'%v) for k,v in mx.items()},'first>1e-7',first); return R,r,o,g,s
