from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
# which state affects trend? J
N=301; wo=mkwo(800,1,60,200); r,o,g,a=run(dev(),wo[:N],34.)
def st(a): r,o,g,b=run(dev(),wo[N:N+1],34.,seq=[N+1],args=a.copy()); return o['trendrate'][0]
b=st(a)
for f in ARGS.names:
    if ARGS[f].base.kind!='f': continue
    n=int(np.prod(ARGS[f].shape)) if ARGS[f].shape else 1
    J=[]
    for i in range(n):
        q=a.copy(); arr=q[f].reshape(-1); arr[i]+=1e-4 if np.isfinite(arr[i]) else 0; J.append((st(q)-b)/1e-4)
    J=np.array(J)
    if np.any(np.abs(J)>1e-7): print(f,[(i,round(float(j)*5,5)) for i,j in enumerate(J) if abs(j)>1e-7][:20], b)
