from lab import *
rng=np.random.default_rng(1)
def ctxp(w,pre): X=np.vstack([pre,w]); r,o,g,a=run(dev(),X.astype('u2'),34.); return int(o['data_type'][-1]),a
pre=np.full((12,30),10000)
def pred(w):
    w=w.astype(float); i=int(np.argmax(w)); rest=np.delete(w,i).mean()
    s2=np.delete(w,[i,i+1] if i<29 else [i]).std(ddof=1)
    return i<=1 and w[i]>1.2*rest and w.std(ddof=1)>1.5*s2
bad=[];n=[0,0]
for tr in range(8000):
    base=rng.integers(2800,15000); nz=rng.choice([0,3,30,100,500]); w=base+rng.normal(0,nz,30)
    k=rng.integers(0,4); js=rng.choice(np.arange(0,6),k,replace=False); w[js]+=base*rng.uniform(-.3,1,k)
    if rng.random()<.5: w[:3]+=base*np.sort(rng.uniform(0,.6,3))[::-1]
    if rng.random()<.3: w+=np.linspace(base*rng.uniform(0,.4),0,30)
    w=np.clip(w,0,65000).astype('u2'); v=ctxp(w,pre)[0]; n[v==2]+=1
    if (v==2)!=pred(w): bad.append((v,w))
print(n,len(bad)); 
for v,w in bad[:8]: w=w.astype(float);i=np.argmax(w);print(v,w.astype(int).tolist(),i,w[i]/np.delete(w,i).mean(),w.std(ddof=1)/np.delete(w,[i,i+1]).std(ddof=1), w.std(ddof=1)/w.mean())
