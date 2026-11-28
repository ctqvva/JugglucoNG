from sc import *
import random
np.set_printoptions(linewidth=230,precision=6,suppress=True)
# collect MAD==0 decisions from vendor across low-noise runs
R=random.Random(5); rows=[]
for it in range(12):
    spec=dict(seed=R.randrange(1,10**9),readings=1500,noise=5,vref=R.uniform(1.41,1.59),lo=60,hi=250)
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    for q in range(1,len(seq)):
        if seq[q]!=seq[q-1]+1 or o['data_type'][q]==2 or o['data_type'][q-1]==2: continue
        x=g['tran_inA'][q]; lib=s['prev_outlier_removed_curr'][q][30:]
        raw=np.append(s['prev_current'][q-1],x); cl=np.append(s['prev_new_i_sig'][q-1],lib); fl=list(s['outlier_max_index'][q-1])
        if (t[q]-290)-s['time_10sec_arr'][q-1][85]>=58: continue
        for j in range(30):
            h=cl if sum(1 for f in fl[-6:] if f)>=4 else raw
            z=np.append(h[j:j+5],x[j]); md=np.median(z); mad=np.median(abs(z-md))
            lc=abs(lib[j]-x[j])>1e-12
            if mad==0:
                sg=1.253314*np.mean(abs(z-z.mean())); dv=abs(x[j]-md)
                rows.append((lc,dv,1.5*sg,dv/(1.5*sg) if sg else 0, int(np.sum(z==md)), float(d['vref'][0])))
            fl.append((1 if x[j]>md else -1) if lc else 0)
R_=np.array(rows); print(len(R_))
cl=R_[R_[:,0]==1]; kp=R_[R_[:,0]==0]
print('clipped: min dv',cl[:,1].min(),'min ratio',cl[:,3].min()); print('kept with ratio>1:',(kp[:,3]>1).sum(),'their dv max',kp[kp[:,3]>1][:,1].max() if (kp[:,3]>1).any() else None,'their dv min',kp[kp[:,3]>1][:,1].min() if (kp[:,3]>1).any() else None)
print('kept ratio>1 sample',kp[kp[:,3]>1][:8]); print('clipped small dv sample',cl[np.argsort(cl[:,1])][:8])
