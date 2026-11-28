import numpy as np
def best_split(X,y,minleaf):
    n=len(y); best=(None,None,None); bg=np.inf
    for j in range(X.shape[1]):
        x=X[:,j]; o=np.argsort(x,kind='stable'); xs=x[o]; ys=y[o]
        c=np.cumsum(ys); k=np.arange(1,n)
        valid=(xs[1:]!=xs[:-1])&(k>=minleaf)&(n-k>=minleaf)
        if not valid.any(): continue
        pl=c[:-1]/k; pr=(c[-1]-c[:-1])/(n-k)
        gini=k*pl*(1-pl)+(n-k)*pr*(1-pr)
        gini[~valid]=np.inf; i=np.argmin(gini)
        if gini[i]<bg: bg=gini[i]; best=(j,(xs[i]+xs[i+1])/2,(xs[i],xs[i+1]))
    return best
def grow(X,y,names,depth=0,maxd=6,minleaf=5,pre=''):
    p=y.mean(); 
    if depth==maxd or p in (0,1) or len(y)<2*minleaf:
        print(pre+'leaf n=%d pos=%d'%(len(y),y.sum())); return
    j,thr,pair=best_split(X,y,minleaf)
    if j is None: print(pre+'leaf n=%d pos=%d'%(len(y),y.sum())); return
    m=X[:,j]<=thr
    print(pre+'%s <= %.6g  (between %.6g and %.6g)  n=%d pos=%d'%(names[j],thr,pair[0],pair[1],len(y),y.sum()))
    grow(X[m],y[m],names,depth+1,maxd,minleaf,pre+'  |')
    print(pre+'%s > %.6g'%(names[j],thr))
    grow(X[~m],y[~m],names,depth+1,maxd,minleaf,pre+'  |')
