from sc import *
import random,time
R=random.Random(77); rows=[]
t0=time.time()
for it in range(700):
    lo=R.choice([40,45,50,55,60,70,80,90,100]); hi=lo+R.choice([20,40,60,90,120,160,220,300,400])
    spec=dict(seed=R.randrange(1,10**9),readings=1600,start=R.choice([420,600,800]),lo=lo,hi=hi,knot_min=R.choice([3,10,30]),knot_max=R.choice([30,60,200]),noise=12)
    spec['knot_max']=max(spec['knot_max'],spec['knot_min']*2)
    seqs,times,adc,temp=scenarios.generate(**spec); dv=co.default_device()
    cnt,out,dbg,st,_=REF.run(dv,co.records(seqs,times,np.array(adc,'u2'),temp))
    a=st[0]; rows.append((a['diabetes_TAR'],a['diabetes_TBR'],a['diabetes_CV'],a['diabetes_mean_x'],a['diabetes_level_diabetes'],a['diabetes_cnt_idx']))
np.save('lvl2.npy',np.array(rows,float)); print(time.time()-t0)
