from ref import *
from gen import mkwo
np.set_printoptions(linewidth=200,precision=4,suppress=True)
E_=[];NE=[]
for seed in range(4):
  for nz in (200,300,400,600):
    wo=mkwo(1500,seed,noise=nz); r,o,g,a,s=run(dev(),wo,34.,snap=True)
    st=s['state_init_kalman'].astype(int); roc=s['kalman_roc']; z=g['out_rescale']; w=s['warmup_cnt'].astype(int)
    for i in range(12,1500):
        if st[i-1]==0 and abs(roc[i,0])>5: 
            K=s['out_kalman'][i-1]; p0=E5*K[0]+(1-E5)*K[1]; (E_ if st[i]==3 else NE).append((i+1,w[i-1],z[i]-p0,*roc[i],z[i],K[2],g['opcal_ad'][i-1]-z[i],o['errcode'][i],g['cal_state'][i]))
E_=np.array(E_);NE=np.array(NE); print(len(E_),len(NE))
print('w enter',np.unique(E_[:,1]).astype(int)[:40]); print('w notenter',np.unique(NE[:,1]).astype(int)[:40])
for c,n in enumerate(['i','w','inn','r0','r1','r2','r3','z','k2','ad-z','err']): print(n,'E',np.abs(E_[:,c]).min(),np.abs(E_[:,c]).max(),'N',np.abs(NE[:,c]).min(),np.abs(NE[:,c]).max())
print(NE[:14]); print(E_[:8])
print('=====')
for nm,f in (('mean prev3',lambda r:r[:,1:4].mean(1)),('mean4',lambda r:r.mean(1)),('mean r0..r2',lambda r:r[:,:3].mean(1)),('median3',lambda r:np.median(r[:,1:4],axis=1)),('wm',lambda r:r[:,1:3].mean(1))):
    e=np.sort(f(E_[:,3:7])*np.sign(E_[:,3]))[::-1]; n=np.sort(f(NE[:,3:7])*np.sign(NE[:,3])); print(nm,'E max',e[:5],'N min',n[:5])
