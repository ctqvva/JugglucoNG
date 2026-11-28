from e7 import *
obs=[]
for tr in range(30):
    w1=rng.normal(10000,20,30).astype('u2'); q,m,oi,x,mmv,q0=two(w1)
    full=np.concatenate([w0,w1]).astype(float)
    for j in range(30):
        if abs(q[30+j]-w1[j])>1e-6: obs.append((full,30+j,q[30+j],q))
print(len(obs))
res=[]
for src in ('raw','clean'):
  for b in range(0,12):
    for f in range(0,12):
        ks=[]
        for full,j,v,q in obs:
            if j<b or j+f>=60 : continue
            z=full[j-b:j+f+1] if src=='raw' else np.concatenate([q[j-b:j],full[j:j+f+1]])
            m=np.median(z); M=np.median(abs(z-m)); sg=np.std(z,ddof=1)
            ks.append((abs(v-m)/M if M else 0,abs(v-np.mean(z))/sg if sg else 0))
        if len(ks)>5:
            k=np.array(ks); res.append((np.std(k[:,0])/np.mean(k[:,0]),'mad',src,b,f,np.mean(k[:,0]),len(k))); res.append((np.std(k[:,1])/np.mean(k[:,1]),'std',src,b,f,np.mean(k[:,1]),len(k)))
res.sort()
for r in res[:8]: print(r)
