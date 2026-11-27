from sc import *
import random, pickle
R=random.Random(9); runs=[]
for it in range(120):
    lo=R.choice([40,60,90,120]); spec=dict(seed=R.randrange(1,10**9),readings=R.choice([800,1500]),start=R.choice([1,30,600,1500,3000]),slope100=R.uniform(2.6,8),eapp=R.choice([0.05,0.1]),
        noise=R.choice([5,12,25,50,100]),lo=lo,hi=lo+R.choice([100,200,300]),knot_min=R.choice([3,6,10]),knot_max=R.choice([10,30,60]))
    spec['knot_max']=max(spec['knot_max'],spec['knot_min']*2)
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    runs.append(dict(spec=spec,seq=seq,typ=o['data_type'].copy(),C=g['err4_condi'].copy(),rng=g['err4_range'].copy(),m5=g['tran_inA_5min'].copy(),mn=g['err4_min'].copy(),md=g['err4_min_diff'].copy(),R=o['result_glucose'].copy(),err=o['errcode'].copy(),rmax=g['tran_inA'].max(1)))
pickle.dump(runs,open('c1runs.pkl','wb'))
pos=sum(int(r['C'][:,1].sum()) for r in runs); print('C1 positives',pos,'runs with C1',sum(r['C'][:,1].any() for r in runs))
for r in runs:
    i=np.nonzero(r['C'][:,1])[0]
    if len(i): 
        i=i[0]; print('start',r['spec']['start'],'seq',r['seq'][i],'range',np.round(r['rng'][i-3:i+1],3),'5min',np.round(r['m5'][i-3:i+1],2),'R %.0f'%r['R'][i])
