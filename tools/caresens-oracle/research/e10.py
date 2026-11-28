from e7 import *
rows=[]
for tr in range(60):
    w1=rng.normal(10000,20,30).astype('u2'); q,m,oi,x,mmv,q0=two(w1)
    full=np.concatenate([w0,w1]).astype(float)
    for j in range(30):
        for src in ('raw','clean'):
            z=np.append(full[30+j-5:30+j] if src=='raw' else q[30+j-5:30+j],full[30+j])
            med=np.median(z);s=1.486*np.median(abs(z-med)); pr=z[4]; xx=z[5]
            rows.append((src,j,abs(q[30+j]-xx)>1e-6,abs(xx-pr)/s if s else 99,abs(xx-med)/s if s else 99,(q[30+j]-pr)/s if s else 99, xx-pr,s,q[30+j]-xx))
for src in('raw','clean'):
    R=[r for r in rows if r[0]==src]
    c=np.array([r[3:6] for r in R if r[2]]);n=np.array([r[3:6] for r in R if not r[2]])
    print(src,len(c),'chg |x-prev|/s min',c[:,0].min(),'unch max',n[:,0].max(),' |x-med|/s',c[:,1].min(),n[:,1].max(),'frac +-1', np.mean(abs(abs(c[:,2])-1)<1e-3))
    for r in [r for r in R if r[2] and abs(abs(r[5])-1)>1e-3][:8]: print(r)
