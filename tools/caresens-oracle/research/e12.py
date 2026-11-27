from lab import *
np.set_printoptions(linewidth=190,precision=4,suppress=True)
def clip(prev_raw,prev_clean,x):
    raw=list(prev_raw);cl=list(prev_clean)
    for v in x:
        z=np.array(raw[-5:]+[v]); md=np.median(z); s=1.486*np.median(abs(z-md))
        if abs(v-md)>1.5*s: n=cl[-1]+math.copysign(s,v-md)
        else: n=v
        raw.append(v);cl.append(n)
    return np.array(cl[5:])
if __name__=='__main__':
    rng=np.random.default_rng(6); d=dev(); tot=bad=0
    for kind in range(4):
        N=300
        wo=10000+rng.normal(0,[5,30,300,30][kind],(N,30))+(np.arange(N*30).reshape(N,30)%997*4 if kind==3 else 0)
        r,o,g,a,s=run(d,wo.astype('u2'),34.0,snap=True)
        for i in range(1,N):
            x=g['tran_inA'][i]; pr=s['prev_current'][i-1];pc=s['prev_new_i_sig'][i-1]
            mine=clip(pr,pc,x); q=s['prev_outlier_removed_curr'][i][30:]
            e=np.abs(mine-q); tot+=1
            if e.max()>1e-9:
                bad+=1
                if bad<8: j=np.argmax(e>1e-9); print(kind,i,j,(x[j]-q[j]),(mine[j]-q[j]),s['outlier_max_index'][i-1:i+1],e.nonzero()[0], 'pc',pc,pr)
    print(tot,bad)
