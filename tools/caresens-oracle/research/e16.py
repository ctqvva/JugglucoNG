from lab import *
np.set_printoptions(linewidth=190,precision=4,suppress=True)
def clip(prev_raw,prev_clean,flags,x):
    raw=list(prev_raw);cl=list(prev_clean);fl=list(flags)
    for v in x:
        h=cl if sum(1 for q in fl[-6:] if q)>=4 else raw
        z=np.array(h[-5:]+[v]); md=np.median(z); s=1.486*np.median(abs(z-md))
        if s==0: s=1.253314*np.mean(abs(z-np.mean(z)))
        if abs(v-md)>1.5*s: n=cl[-1]+math.copysign(s,v-md); f=1 if v>md else -1
        else: n=v;f=0
        raw.append(v);cl.append(n);fl.append(f)
    return np.array(cl[5:]),fl[-6:]
if __name__=='__main__':
    rng=np.random.default_rng(6); d=dev(); tot=bad=fb=0
    for kind in range(6):
        N=3000
        wo=10000+rng.normal(0,[5,30,300,30,2,1][kind],(N,30))+(np.arange(N*30).reshape(N,30)%997*4 if kind==3 else 0)
        r,o,g,a,s=run(d,wo.astype('u2'),34.0,snap=True)
        for i in range(1,N):
            x=g['tran_inA'][i]; pr=s['prev_current'][i-1];pc=s['prev_new_i_sig'][i-1]
            mine,fl=clip(pr,pc,s['outlier_max_index'][i-1],x); q=s['prev_outlier_removed_curr'][i][30:]
            er=np.abs(mine-q); tot+=1; fb+=fl!=list(s['outlier_max_index'][i])
            if er.max()>1e-9:
                bad+=1; j=np.argmax(er>1e-9)
                if bad<9: print(kind,i,j,(x[j]-q[j]),(mine[j]-q[j]))
    print(tot,bad,fb)
