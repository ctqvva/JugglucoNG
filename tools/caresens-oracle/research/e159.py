from sc import *
from kt import kt
import random
R=random.Random(7); C={'raw':0,'smooth':0,'both':0,'n':0,'dt4':0}
for it in range(40):
    lo=R.choice([30,40,50]); spec=dict(seed=R.randrange(1,10**9),readings=1500,lo=lo,hi=lo+R.choice([60,150,400]),knot_min=3,knot_max=R.choice([6,30]),noise=R.choice([12,50]))
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    G=o['result_glucose']; acc=np.nonzero(o['data_type']!=2)[0]
    for ii in range(12,len(acc)):
        i=acc[ii]; win=acc[ii-6:ii+1]; raw=G[win]; S=s['smooth_sig_in'][i][3:]; tr=o['trendrate'][i]
        Tm=(s['smooth_time_in'][i][9].astype(np.int64)-s['smooth_time_in'][i][3])/60
        if Tm>35: continue
        p_raw=((raw<40)|(raw>500)).any(); p_s=((S<40)|(S>500)).any(); p_dt=(o['data_type'][win]==4).any()
        is100=tr==100
        C['n']+=1; C['raw']+=is100==(p_raw or p_s); C['smooth']+=is100==p_s; C['dt4']+=is100==(p_dt or p_s); C['both']+=is100==(p_raw)
print(C)
