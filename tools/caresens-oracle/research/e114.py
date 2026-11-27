from gen import *
N=4320
for S0 in (2017,2100,2150,2190,2200,2210,2250,2300,2500,2880,2900,3000,3168,3200,3400,3456,3500,3744,3800,4000,4032,4100):
    w=mkwo(N,1,level=150).astype(float); w[S0-1-40:]=2756+(w[S0-1-40:]-2756)*.4; W=w.clip(0,65535).astype('u2')
    r,o,g,a,s=run(dev(),W,34.,snap=True)
    x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc'][2100]/1152
    act=np.nonzero(np.abs(dl)>0)[0]
    print(S0,'first act seq',act[0]+1 if len(act) else None,'lp/mdc at act %.5f'%(lp[act[0]]/mdc) if len(act) else '', 'prev %.5f'%(lp[act[0]-1]/mdc) if len(act) else '', 'errs',np.unique(o['errcode']))
