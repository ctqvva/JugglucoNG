from ref import *
from gen import mkwo
np.set_printoptions(linewidth=200,precision=5,suppress=True)
ev=[]
for seed in range(3):
  for nz in (200,300,400,600):
    wo=mkwo(1500,seed,noise=nz); r,o,g,a,s=run(dev(),wo,34.,snap=True)
    w=s['warmup_cnt'].astype(int); b=[i for i in range(12,1500) if w[i]==1]
    for i in b: 
        ev.append((i,g['out_rescale'][i],s['kalman_roc'][i],s['kalman_state_fluctuation'][i],s['init_cg_prev'][i],g['state_init_kalman'][i],o['errcode'][i],o['errcode'][i-1]))
        if len(ev)<14: print(nz,i+1,'z',g['out_rescale'][i],'prevz',g['out_rescale'][i-1],'K-',s['out_kalman'][i-1],'K',s['out_kalman'][i],'roc',s['kalman_roc'][i],'fl',s['kalman_state_fluctuation'][i],'st',g['state_init_kalman'][i],'err',o['errcode'][i-1],o['errcode'][i],'w',w[i-1],w[i+1 if i<1499 else i])
print(len(ev))
wo=mkwo(1500,0,noise=300); r,o,g,a,s=run(dev(),wo,34.,snap=True); print('st values',np.unique(g['state_init_kalman']),'fluct',np.unique(s['kalman_state_fluctuation'])[:5]); 
