from lab import *
np.set_printoptions(linewidth=200,precision=6,suppress=True)
from e16 import clip
X=np.arange(90.);D=np.abs(X[:,None]-X[None])
Kq=np.clip(1-(D/np.sort(D,axis=1)[:,86][:,None])**3,0,1)**3
def mm(x):
    n=len(x); return np.array([np.median(x[max(0,i-3):min(n,i+3)]) for i in range(n)])
def tm(x): return (np.sum(x)-np.min(x)-np.max(x))/(len(x)-2)
def loess(y):
    dl=np.ones(90);U=X[None]-X[:,None]
    for it in range(3):
        W=Kq*dl; S0=W.sum(1);S1=(W*U).sum(1);S2=(W*U*U).sum(1);T0=(W*y).sum(1);T1=(W*y*U).sum(1)
        f=(S2*T0-S1*T1)/(S0*S2-S1*S1); r=y-f; m=np.median(abs(r))
        if m>0: dl=np.clip(1-(r/(6*m))**2,0,1)**2
    return f
K7=np.array([-1,4,7,8,7,4,-1.])
def smk(m,p3):
    x=np.append(p3,m); n=len(x); out=[]
    for i in range(3,n):
        idx=np.arange(i-3,i+4); ok=(idx>=0)&(idx<n); out.append((K7[ok]*x[idx[ok]]).sum()/K7[ok].sum())
    return np.array(out)
def gm(q): return np.concatenate([mm(z) for z in q.reshape(5,6)])
def mins(m): return np.array([tm(z) for z in m.reshape(5,6)])
if __name__=='__main__':
    rng=np.random.default_rng(1); d=dev(); wo=rng.normal(10000,30,(40,30)).astype('u2'); wo+=(np.arange(40)*50)[:,None].astype('u2')
    r,o,g,a,s=run(d,wo,34.0,snap=True)
    q=s['prev_outlier_removed_curr'][1][30:]; t=g['tran_inA_1min'][1]; p3=s['prev_mov_median_curr'][0]
    print(t,mins(smk(gm(q),p3))-t,mins(gm(q))-t)
    e=[]
    for i in range(2,40):
        y=np.append(s['prev_outlier_removed_curr'][i-1],s['prev_outlier_removed_curr'][i][30:]); p3=s['prev_mov_median_curr'][i-1]
        e.append(np.abs(mins(smk(gm(loess(y)[60:]),p3))-g['tran_inA_1min'][i]).max())
    print(np.array(e).max(), np.abs(s['prev_mov_median_curr'][5]-gm(loess(np.append(s['prev_outlier_removed_curr'][4],s['prev_outlier_removed_curr'][5][30:]))[60:])[-3:]).max())
    # timing: gaps
    for gap in (300,301,305,310,330,350,360,390,420,450,500,550,600,900,1200,1500):
        sq=np.arange(1,7); sq[5]=5+max(1,round(gap/300)); tt=BASE+300*np.arange(1,7); tt[5]=tt[4]+gap
        r,o,g,a,s=run(d,wo[:6],34.0,seq=sq,t=tt,snap=True)
        x=g['tran_inA'][5]; q=s['prev_outlier_removed_curr'][5]; p3=s['prev_mov_median_curr'][4]
        c=clip(s['prev_current'][4],s['prev_new_i_sig'][4],s['outlier_max_index'][4],x)[0]
        t=g['tran_inA_1min'][5]
        pq=s['prev_outlier_removed_curr'][4]
        mdl=dict(raw=mins(gm(x)),clipsm=mins(smk(gm(c),p3)),clip=mins(gm(c)),lo=mins(smk(gm(loess(np.append(pq,c))[60:]),p3)))
        print(gap,sq[5],'datat',o['data_type'][5],'ec',o['errcode'][5],'prevkept',np.abs(q[:30]-pq[30:]).max()<1e-9,'clipped',np.abs(q[30:]-c).max()<1e-9,{k:float('%.1e'%np.abs(v-t).max()) for k,v in mdl.items()}, (s['time_10sec_arr'][5].astype('i8')-BASE)[[0,29,30,59,60,89]])
