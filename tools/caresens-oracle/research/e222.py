from sc import *
import make_fixtures as mf
from err16lib import *
for name in ('gaps-and-jitter','holdout-gaps-spikes','clock-jump','contact-spikes'):
    spec=mf.DEVELOPMENT.get(name) or mf.HOLDOUT[name]
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    sl=float(d['slope100'][0]); R=o['result_glucose']; X=g['tran_inA_5min']; P=g['err16_CGM_plasma']; I=g['err16_CGM_ISF_smooth']
    acc=np.nonzero(o['data_type']!=2)[0]   # accepted records
    badP=[];badI=[];nanP=0
    for n,i in enumerate(acc):
        if n<11: continue
        h=acc[n-11:n+1][::-1]
        p=round(kern(np.round(R[h]))); q=round(kern(X[h]*100/sl))
        if np.isnan(P[i]): nanP+=1; continue
        if p!=P[i]: badP.append(i)
        if q!=I[i]: badI.append(i)
    print(name,'accepted',len(acc),'P bad',len(badP),badP[:5],'I bad',len(badI),badI[:5],'P nan',nanP, 'nan idx', np.nonzero(np.isnan(P))[0][:15])
