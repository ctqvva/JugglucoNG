from e14 import *
rng=np.random.default_rng(6); d=dev(); cc=Counter()
def pred(h,v,lastc):
    z=np.array(list(h)+[v]); md=np.median(z); s=1.486*np.median(abs(z-md))
    if s==0: s=1.253314*np.mean(abs(z-np.mean(z)))
    if abs(v-md)>1.5*s: return lastc+math.copysign(s,v-md)
    return v
for kind in range(3):
    N=1500
    wo=10000+rng.normal(0,[5,30,300][kind],(N,30))
    r,o,g,a,s=run(d,wo.astype('u2'),34.0,snap=True)
    R=g['tran_inA'].reshape(-1);C=s['prev_outlier_removed_curr'][:,30:].reshape(-1)
    F=np.where(np.abs(R-C)>1e-9,np.sign(R-C),0).astype(int)
    for k in range(40,len(R)):
        a_=pred(R[k-5:k],R[k],C[k-1]);b_=pred(C[k-5:k],R[k],C[k-1])
        if abs(a_-b_)>1e-9 :
            w='raw' if abs(a_-C[k])<1e-9 else 'clean' if abs(b_-C[k])<1e-9 else 'none'
            f=tuple(F[k-6:k]); cc[(f[1:],w)]+=1
keys=sorted(set(k[0] for k in cc))
for f in keys:
    c={w:cc[(f,w)] for w in('raw','clean','none') if cc[(f,w)]}
    if 'clean' in c or 'none' in c: print(f,c)
print('raw only with >=3:',[f for f in keys if sum(map(bool,f))>=3 and not cc[(f,'clean')]])
