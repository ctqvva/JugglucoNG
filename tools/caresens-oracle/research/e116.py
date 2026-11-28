from gen import *
np.set_printoptions(linewidth=230,precision=5,suppress=True)
N=4320
def cser(W,label):
    r,o,g,a,s=run(dev(),W,34.,snap=True)
    x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc'][2100]/1152
    sq=np.arange(1,N+1); w=np.minimum(1,(sq-2016)/288.)
    c=(dl/w+lp)/mdc; act=np.abs(dl)>0
    idx=np.nonzero(act)[0]
    print(label,[(int(k+1),round(float(c[k]),5),int(o['errcode'][k])) for k in idx[::max(1,len(idx)//14)]])
dec=np.linspace(1,.3,N)[:,None]; w=mkwo(N,1,80,250); cser((2756+(w-2756.)*dec).clip(0,65535).astype('u2'),'decay.3')
for S0 in (2017,2100,3000):
    w=mkwo(N,1,level=150).astype(float); w[S0-1-40:]=2756+(w[S0-1-40:]-2756)*.4; cser(w.clip(0,65535).astype('u2'),'drop@%d'%S0)
