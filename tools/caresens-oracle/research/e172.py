from sc import *
from kt import kt
import random
R=random.Random(3); bad=0; tot=0
for it in range(40):
    spec=dict(seed=R.randrange(1,10**9),readings=R.choice([1500,3000,4320]),gap_permille=R.choice([0,20]),gap_max=5,spike_permille=R.choice([0,10]),jitter=R.choice([0,25]),lo=R.choice([50,70]),hi=R.choice([200,350]),decay=R.choice([1,.7]),noise=R.choice([12,25]))
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    for kk in (R.randrange(2,40),R.randrange(40,800),R.randrange(800,len(seq)-20)):
        if c[kk-1]>1: continue
        k=kt(d,adc[kk:].astype('u2'),T[kk:],seq[kk:],t[kk:],vstate=s[kk-1:kk])
        oo=o[kk:]; ok=(oo['data_type']!=2)&(k['exact']==1)
        e=np.abs(k['g']-oo['result_glucose'])*ok; et=np.abs(np.nan_to_num(k['tr'])-oo['trendrate'])*ok; eh=np.abs(k['h'][:,0]-oo['smooth_result_glucose'][:,0])*ok
        tot+=1
        if max(e.max(),et.max(),eh.max())>1e-7 or ((k['err']^oo['errcode'])&96).any() or ((k['dt']==2)!=(oo['data_type']==2)).any():
            bad+=1
            if bad<6: print('import at',kk,'seq',seq[kk],'first glu diff',np.argmax(e>1e-7),'trend',np.argmax(et>1e-7),'hist',np.argmax(eh>1e-7), 'err', np.argmax(((k['err']^oo['errcode'])&96)>0), spec)
print('imports',tot,'mismatching',bad)
