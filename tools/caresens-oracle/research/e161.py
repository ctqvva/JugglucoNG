from sc import *
import random
from collections import Counter
R=random.Random(3); C=Counter(); ex=[]
for it in range(30):
    lo=R.choice([0,10,20,30]); spec=dict(seed=R.randrange(1,10**9),readings=1200,lo=lo,hi=lo+R.choice([40,100]),knot_min=3,knot_max=R.choice([6,30]),noise=R.choice([12,50]),start=R.choice([1,300,1000]))
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    G=o['result_glucose']
    for i in range(1,len(seq)):
        if o['data_type'][i] not in (0,4) or G[i]>=40: continue
        fl=o['data_type'][i]==4; key=(fl,int(o['errcode'][i]),int(G[i-1]<40),int(o['data_type'][i-1]==4))
        C[key]+=1
        if len(ex)<10 and not fl and o['errcode'][i]==0: ex.append((seq[i],G[i-3:i+1].round(1),o['data_type'][i-3:i+1],o['errcode'][i]))
for k,v in sorted(C.items()): print(k,v)
for e in ex: print(e)
