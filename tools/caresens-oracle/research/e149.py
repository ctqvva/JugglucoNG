from gen import *
wo=mkwo(80,1)
def tst(gapafter, spike=True):
    sq=np.arange(1,61); W=wo[:60].copy()
    if spike: W[40,0]=int(W[40].max()*1.6)
    sq[41:]+=gapafter
    r,o,g,a,s=run(dev(),W,34.,seq=sq,snap=True)
    return o['data_type'][40],[int(s['warmup_cnt'][k]) for k in (39,41,42)]
for gp in (0,1,2,3,4,5): print('spike, extra gap',gp,tst(gp),' no spike',tst(gp,False))
