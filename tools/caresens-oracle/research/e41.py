from cur import *
rng=np.random.default_rng(1); d=dev(); wo=rng.normal(10000,30,(60,30)).astype('u2')
from collections import Counter
# reading 5 after gap; compare clip with hist but different details
C=Counter();n=0
for tr in range(40):
    W=rng.normal(10000,30,(6,30)).astype('u2'); tt=BASE+300*np.arange(1,7); tt[5]+=3000; sq=np.arange(1,7); sq[5]=16
    r,o,g,a,s=run(d,W,34.0,seq=sq,t=tt,snap=True)
    x=g['tran_inA'][5];q=s['prev_outlier_removed_curr'][5][30:]
    ch=np.nonzero(np.abs(q-x)>1e-9)[0]; C.update(ch.tolist())
print('seqgap',C)
C=Counter()
for tr in range(40):
    W=rng.normal(10000,30,(6,30)).astype('u2'); tt=BASE+300*np.arange(1,7); tt[5]+=20; sq=np.arange(1,7)
    r,o,g,a,s=run(d,W,34.0,seq=sq,t=tt,snap=True)
    x=g['tran_inA'][5];q=s['prev_outlier_removed_curr'][5][30:]
    c=clip(s['prev_current'][4],s['prev_new_i_sig'][4],s['outlier_max_index'][4],x)[0]
    ch=np.nonzero(np.abs(q-x)>1e-9)[0]; C.update(ch.tolist())
    if tr<4: print(ch,np.nonzero(np.abs(c-x)>1e-9)[0],(q-x)[ch],(c-x)[ch], s['outlier_max_index'][4:6])
print('tgap',C)
C=Counter()
for tr in range(40):
    W=rng.normal(10000,30,(1,30)).astype('u2'); 
    r,o,g,a,s=run(d,W,34.0,snap=True); C.update(np.nonzero(np.abs(s['prev_outlier_removed_curr'][0][30:]-g['tran_inA'][0])>1e-9)[0].tolist())
print('first',C)
