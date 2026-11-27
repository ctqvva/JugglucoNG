import numpy as np
def conj(X,y,names,steps=4):
    """greedy AND rule: each step picks feature/threshold keeping all positives, removing most negatives"""
    keep=np.ones(len(y),bool); rule=[]
    for _ in range(steps):
        best=None
        for j in range(X.shape[1]):
            x=X[:,j]; p=x[y&keep]; 
            if np.isnan(p).any(): continue
            lo,hi=p.min(),p.max()
            for op,thr,mask in (('>=',lo,x>=lo),('<=',hi,x<=hi)):
                mask=mask&keep
                neg=(mask&~y).sum()
                if best is None or neg<best[0]: best=(neg,names[j],op,thr,mask,j)
        neg,nm,op,thr,mask,j=best
        # nearest negative beyond threshold (gap)
        x=X[:,j]; out=keep&~mask&~y
        gap = (x[out].max() if op=='>=' else x[out].min()) if out.any() else None
        rule.append((nm,op,thr,'closest excluded neg',gap,'neg left',neg)); keep=mask
        if neg==0: break
    return rule
