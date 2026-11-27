from e56 import *
print('=====')
def one(sg,tg,i0=40):
    sq=np.arange(1,46); sq[40:]+=sg-1; t=BASE+300*np.arange(1,46); t[40:]+=tg-300
    r,o,g,a,s=run(dev(),wo[:45],34.,seq=sq,t=t,snap=True); return (int(r[40]),int(o['data_type'][40]),int(o['errcode'][40]),int(s['warmup_cnt'][40]),int(o['seq_number_final'][40]),int(o['seq_number_final'][41]),int(r[41]))
prev=None
for sg in (1,2,3):
    prev=None
    for tg in list(range(-900,4000)): 
        v=one(sg,tg); 
        if v!=prev: print(sg,tg,v); prev=v
