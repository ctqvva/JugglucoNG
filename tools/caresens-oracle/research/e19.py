from e16 import *
def rlow(y,deg,its,c=6.0,div=1.0,x=None):
    n=len(y); x=np.arange(n,dtype=float) if x is None else x
    delta=np.ones(n); f=np.zeros(n)
    for it in range(its+1):
        for i in range(n):
            dd=np.abs(x-x[i]); h=dd.max()*div; w=(1-(dd/h)**3)**3; w[w<0]=0; w=w*delta
            V=np.vander(x-x[i],deg+1,increasing=True); sw=np.sqrt(w)
            f[i]=np.linalg.lstsq(V*sw[:,None],y*sw,rcond=None)[0][0]
        r=np.abs(y-f); m=np.median(r)
        if it<its:
            if m<1e-15: break
            u=r/(c*m); delta=np.where(u<1,(1-u*u)**2,0)
        yield it,f.copy()
if __name__=='__main__':
    rng=np.random.default_rng(1); d=dev(); wo=rng.normal(10000,30,(12,30)).astype('u2')
    r,o,g,a,s=run(d,wo,34.0,snap=True)
    for i in (5,9):
        y=np.append(s['prev_outlier_removed_curr'][i-1],s['prev_outlier_removed_curr'][i][30:])
        print('lib',g['tran_inA_1min'][i],s['prev_mov_median_curr'][i])
        for deg in (1,2):
            for c in (6.0,):
                for it,f in rlow(y,deg,6,c):
                    print(deg,c,it,[tm(q) if 0 else (q.sum()-q.min()-q.max())/4 for q in f[60:].reshape(5,6)],f[-3:])
