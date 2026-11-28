from ref import *
from gen import mkwo
from collections import Counter
ent=[];noent=[];rs=[];nors=[];fin=Counter(); odd=[]
for seed in range(4):
  for nz in (100,200,300,400,600):
    wo=mkwo(1500,seed,noise=nz); r,o,g,a,s=run(dev(),wo,34.,snap=True)
    st=s['state_init_kalman'].astype(int); fl=s['kalman_state_fluctuation']; roc=s['kalman_roc'][:,0]; z=g['out_rescale']
    for i in range(12,1500):
        rr=abs(roc[i])
        if st[i-1]==0: (ent if st[i]==3 else noent).append((rr,s['kalman_roc'][i],i,s['warmup_cnt'][i]))
        else:
            if fl[i]==1: rs.append(rr)
            elif fl[i]==fl[i-1]+1: nors.append(rr)
            elif fl[i]==0: fin[(fl[i-1],st[i])]+=1; nors.append(rr)
            else: odd.append((fl[i-1],fl[i]))
ent.sort(key=lambda x:x[0]); noent.sort(key=lambda x:-x[0])
print('enter min',[(e[0],e[1],e[3]) for e in ent[:4]],'noenter max',[(e[0],e[1],e[3]) for e in noent[:4]],len(ent),len(noent)); print('reset min',min(rs),'no reset max',max(nors),fin,Counter(odd))
