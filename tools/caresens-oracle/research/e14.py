from lab import *
np.set_printoptions(linewidth=190,precision=4,suppress=True)
from collections import Counter
def clip(prev_raw,prev_clean,flags,x,nf,K,log=None):
    raw=list(prev_raw);cl=list(prev_clean);fl=list(flags)
    for v in x:
        cnt=sum(1 for f in fl[-nf:] if f)
        h=cl if cnt>=3 else raw
        z=np.array(h[-5:]+[v]); md=np.median(z); s=1.486*np.median(abs(z-md)); c=0
        if s==0: s=K*np.mean(abs(z-np.mean(z))); c=1
        if abs(v-md)>1.5*s: n=cl[-1]+math.copysign(s,v-md); f=1 if v>md else -1
        else: n=v;f=0
        raw.append(v);cl.append(n);fl.append(f)
        if log is not None: log.append((cnt,c,abs(v-md)/s if s else -1,z-md))
    return np.array(cl[5:]),fl[-6:]
if __name__=='__main__':
  for nf,K in ((5,1.253314),(6,1.253314)):
    rng=np.random.default_rng(6); d=dev(); tot=bad=fb=0;cc=Counter()
    for kind in range(5):
        N=300
        wo=10000+rng.normal(0,[5,30,300,30,2][kind],(N,30))+(np.arange(N*30).reshape(N,30)%997*4 if kind==3 else 0)
        r,o,g,a,s=run(d,wo.astype('u2'),34.0,snap=True)
        for i in range(1,N):
            x=g['tran_inA'][i]; pr=s['prev_current'][i-1];pc=s['prev_new_i_sig'][i-1]
            log=[]
            mine,fl=clip(pr,pc,s['outlier_max_index'][i-1],x,nf,K,log); q=s['prev_outlier_removed_curr'][i][30:]
            er=np.abs(mine-q); tot+=1; fb+=fl!=list(s['outlier_max_index'][i])
            if er.max()>1e-9:
                bad+=1; j=np.argmax(er>1e-9); cc[(log[j][0],log[j][1])]+=1
                if bad<6: print(kind,i,j,log[j],(x[j]-q[j]),(mine[j]-q[j]))
    print(nf,K,tot,bad,fb,cc)
