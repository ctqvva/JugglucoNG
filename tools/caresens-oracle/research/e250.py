import craft
from craft import *
craft.DEV=co.default_device()
spec=dict(BASE,readings=2600)
seq,t,adc,T=[np.array(x) for x in scenarios.generate(**spec)]; adc=adc.astype(np.int64)
rng=np.random.default_rng(3)
def probe(c,noise=0,per_rec=0):
    a=adc.copy(); n=2500-300
    lvl=np.full((n,30),adc_for(c),np.int64)
    if noise: lvl=lvl+rng.integers(-noise,noise+1,lvl.shape)
    if per_rec: lvl=lvl+rng.integers(-per_rec,per_rec+1,(n,1))
    a[300:2500]=lvl
    cc,o,g,_,s=REF.run(craft.DEV,co.records(seq,t,a.astype('u2'),T),snapshots=True); o=o[:,0]; g=g[:,0]
    return [g['err4_delay_condi'][i].tolist() for i in (700,1000,1500,2200)],[g['err4_condi'][i].tolist() for i in (700,1000,1500,2200)],(o['errcode']&4).any()
for c in (0.3,0.8,1.2,1.6,2.0,3.0,5.0):
    for nz,pr in ((0,0),(3,0),(0,3),(20,0),(0,20)):
        D,C,e=probe(c,nz,pr); print('c',c,'noise',nz,'per-rec',pr,'D',D,'C',C,'err4',e)
