from gen import *
np.set_printoptions(linewidth=200,precision=5,suppress=True)
N=4320; out=[]
for f in (.3,.5,.6,.7,.8,.9):
  for seed in (1,2):
    dec=np.linspace(1,f,N)[:,None]; w=mkwo(N,seed,80,250); W=(2756+(w-2756.)*dec).clip(0,65535).astype('u2')
    r,o,g,a,s=run(dev(),W,34.,snap=True)
    x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc'][2100]/1152
    cand=np.nonzero((lp<.95*mdc)&(np.arange(N)>=2016))[0]; act=np.nonzero(np.abs(dl)>0)[0]
    k=cand[0] if len(cand) else None
    feats=lambda k:dict(seq=k+1,ca=s['curr_avg_arr'][k].mean()/mdc,base=g['curr_baseline'][k]/mdc,x=x[k]/mdc,od=g['out_drift'][k]/mdc,day=x[k-287:k+1].mean()/mdc,lp=lp[k]/mdc,y=y[k], hr=x[k-11:k+1].mean()/mdc)
    print(f,seed,'cand',None if k is None else feats(k),'act first',act[0]+1 if len(act) else None, feats(act[0]) if len(act) else '')
