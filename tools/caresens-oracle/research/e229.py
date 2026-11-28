import numpy as np
z=np.load('err16_ds.npz'); X=z['X']; Y=z['Y'].astype(bool); N=[n.replace('err16_CGM_ISF_','').replace('err16_','') for n in z['names']]
c=lambda n: X[:,N.index(n)]
y=Y[:,1]
a=c('trend_mean_rsq')>=0.8; b=c('trend_mean_slope')<=-5
for nm,m in (('rsq&slope',a&b),('rsq&!slope',a&~b),('!rsq&slope',~a&b),('none',~a&~b)):
    print(nm,'pos',(y&m).sum(),'neg',(~y&m).sum())
m=a&b
r=c('trend_mean_ratio'); 
for lo in (-10,-15,-20,-25,-30,-40):
    print('ratio<=',lo,'pos',(y&m&(r<=lo)).sum(),'neg',(~y&m&(r<=lo)).sum())
# other features within rsq&slope among neg vs pos
for n in N:
    x=c(n); p=x[y&m]; q=x[~y&m]
    if len(p) and np.isfinite(p).all(): print('%-32s pos [%.3f, %.3f]  neg [%.3f, %.3f]'%(n,p.min(),p.max(),np.nanmin(q),np.nanmax(q)))
