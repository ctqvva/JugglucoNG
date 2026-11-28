from sc import *
from kt import kt
import random, sys
from collections import Counter
R=random.Random(int(sys.argv[1]) if len(sys.argv)>1 else 7); bad=Counter(); ex={}
N=int(sys.argv[2]) if len(sys.argv)>2 else 60
for it in range(N):
    lo=R.choice([40,50,60,70,90,120]); spec=dict(seed=R.randrange(1,10**9),readings=R.choice([300,800,1500,2500,4320]),start=R.choice([1,1,1,3,30,600,1500]),
        slope100=R.uniform(2.6,8.0),vref=R.uniform(1.41,1.59),eapp=R.uniform(.04,.112),lo=lo,hi=lo+R.choice([30,80,150,250,350]),
        knot_min=R.choice([3,10,30,60]),noise=R.choice([5,12,25,50,100]),temp_mode=R.choice(['const','vary','step']),temp_lo=R.randrange(3000,3500),
        decay=R.choice([1,1,.9,.7,.5]),gap_permille=R.choice([0,0,10,40,100]),gap_max=R.choice([1,3,12,40]),jitter=R.choice([0,0,5,30,45]),
        spike_permille=R.choice([0,0,5,20]),clock_jump_at=R.choice([-1,-1,-1,200]),clock_jump=R.choice([2000,40000]))
    spec['knot_max']=spec['knot_min']*R.choice([2,5,10]); spec['temp_hi']=spec['temp_lo']+R.choice([0,100,300,600])
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    k=kt(d,adc.astype('u2'),T,seq,t)
    fill=np.nonzero(c>1)[0]; end=fill[0] if len(fill) else len(seq)
    ok=(o['data_type']!=2)&((o['errcode']&64)==0); ok[end:]=False; okx=ok&(k['exact']==1)
    gd=np.abs(k['g']-o['result_glucose'])*okx
    td=np.abs(np.nan_to_num(k['tr'])-np.nan_to_num(o['trendrate']))*okx
    hd=np.abs(k['h'][:,0]-o['smooth_result_glucose'][:,0])*okx
    eb=((k['err']^o['errcode'])&(32|64))[:end]; dt2=((k['dt']==2)!=(o['data_type']==2))[:end]; sf=(k['sf']!=o['seq_number_final'])[:end]
    inex=(k['exact']==0)[:end].sum()
    tags=[n for n,v in (('glu',gd.max()>1e-7),('trend',td.max()>1e-7),('hist',hd.max()>1e-7),('err32/64',eb.any()),('reject',dt2.any()),('seq',sf.any()),) if v]
    for tg in tags: bad[tg]+=1; ex.setdefault(tg,(spec,int(np.argmax(gd>1e-7)) if tg=='glu' else None))
    bad['runs']+=1; bad['inexact records']+=int(inex); bad['fills']+=len(fill)>0
print(dict(bad))
for k_,v in ex.items(): print(k_,v)
