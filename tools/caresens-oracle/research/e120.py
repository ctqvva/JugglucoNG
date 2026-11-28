from gen import *
N=4320
def cser(W):
    r,o,g,a,s=run(dev(),W,34.,snap=True)
    x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc'][2100]/1152
    sq=np.arange(1,N+1); w=np.minimum(1,(sq-2016)/288.); c=(dl/w+lp)/mdc; act=np.nonzero(np.abs(dl)>0)[0]
    return int(a['diabetes_level_diabetes'][0]), sorted(set(np.round(c[act],5)))[:3], (act[0]+1 if len(act) else None)
for lo,hi,nz in ((200,450,10),(150,400,10),(40,300,10),(30,250,10),(60,80,10),(100,110,10)):
    w=mkwo(N,3,lo,hi,noise=nz).astype(float); w[2600:]=2756+(w[2600:]-2756)*.4; print(lo,hi,cser(w.clip(0,65535).astype('u2')))
