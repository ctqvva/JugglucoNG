from sc import *
import random
R=random.Random(3); P=[]
for it in range(30):
    lo=R.choice([0,10,20,30]); spec=dict(seed=R.randrange(1,10**9),readings=1200,lo=lo,hi=lo+R.choice([40,100]),knot_min=3,knot_max=R.choice([6,30]),noise=R.choice([12,50]),start=R.choice([1,300,1000]))
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
    for i in range(len(seq)):
        if o['data_type'][i] in (0,4): P.append((o['data_type'][i]==4,o['result_glucose'][i],g['init_cg'][i],g['out_rescale'][i],g['opcal_ad'][i],seq[i],g['tran_inA_5min'][i],g['out_drift'][i]))
P=np.array(P,float); f=P[:,0]==1
for c,n in ((1,'G'),(2,'cg'),(3,'res'),(6,'cur5'),(7,'drift')): print(n,'flag max',P[f,c].max(),'unflag min',P[~f,c].min(),'overlap count',((P[:,c]<P[f,c].max())&~f).sum(),((P[:,c]>P[~f,c].min())&f).sum())
