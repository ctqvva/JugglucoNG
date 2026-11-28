from e75 import *
print('=====')
def feats(w):
    w=w.astype(float);i=int(np.argmax(w)); nb=[j for j in(i-1,i+1) if 0<=j<30]; j=max(nb,key=lambda q:w[q])
    rest=np.delete(w,i); r2=np.delete(w,[i,j]); return i,j,w[i]/rest.mean(),w.std(ddof=1)/r2.std(ddof=1),w.min()/r2.mean(),int(np.argmin(w)),r2.min()/np.median(r2)
A=[];
for tr in range(12000):
    base=rng.integers(2800,15000); nz=rng.choice([0,3,30,100,500]); w=base+rng.normal(0,nz,30)
    k=rng.integers(1,4); js=rng.choice(np.arange(0,6),k,replace=False); w[js]+=base*rng.uniform(-.3,.6,k)
    w=np.clip(w,0,65000).astype('u2'); f=feats(w)
    if f[0]<=1 and f[2]>1.2 and f[3]>1.5: A.append((ctxp(w,pre)[0]==2,)+f+tuple(w[:6]))
A=np.array(A,float); print(len(A),A[:,0].mean())
for c in (5,7): 
    print(c,'flag min',A[A[:,0]==1,c].min(),'unfl max',A[A[:,0]==0,c].max())
print(A[A[:,0]==0][:12].round(3)); 
