from gen import *
np.set_printoptions(linewidth=230,precision=5,suppress=True)
N=4320
def cser(W,label):
    r,o,g,a,s=run(dev(),W,34.,snap=True)
    x=g['corrected_re_current'];y=s['iir_y'];lp=x-y;dl=g['out_iir']-x; mdc=s['sum_curr_for_mdc'][2100]/1152
    sq=np.arange(1,N+1); w=np.minimum(1,(sq-2016)/288.); c=(dl/w+lp)/mdc; act=np.nonzero(np.abs(dl)>0)[0]
    lv=g['level_diabetes']; print(label,'levels',[(int(k+1),int(lv[k])) for k in range(0,N,400)], 'factor',sorted(set(np.round(c[act],5)))[:5], 'TAR TBR CV mean',[ (int(k+1),round(float(g['diabetes_TAR'][k]),3),round(float(g['diabetes_TBR'][k]),3),round(float(g['diabetes_CV'][k]),3),round(float(g['diabetes_mean_x'][k]),1)) for k in (300,1000,2016,2100,3000)])
    return g,s
dec=np.linspace(1,.3,N)[:,None]; w=mkwo(N,1,80,250); cser((2756+(w-2756.)*dec).clip(0,65535).astype('u2'),'decay.3')
dec=np.linspace(1,.6,N)[:,None]; w=mkwo(N,1,80,250); cser((2756+(w-2756.)*dec).clip(0,65535).astype('u2'),'decay.6')
dec=np.linspace(1,.8,N)[:,None]; w=mkwo(N,1,80,250); cser((2756+(w-2756.)*dec).clip(0,65535).astype('u2'),'decay.8')
w=mkwo(N,1,level=150).astype(float); w[2959:]=2756+(w[2959:]-2756)*.4; cser(w.clip(0,65535).astype('u2'),'drop')
