from e20 import *
import itertools
rng=np.random.default_rng(1); d=dev(); wo=rng.normal(10000,30,(14,30)).astype('u2')
r,o,g,a,s=run(d,wo,34.0,snap=True)
Y=[np.append(s['prev_outlier_removed_curr'][i-1],s['prev_outlier_removed_curr'][i][30:]) for i in range(3,14)]
T=[g['tran_inA_1min'][i] for i in range(3,14)]
x=np.arange(90.)
D=np.abs(x[:,None]-x[None])
def tri(h): return np.clip(1-(D/h)**3,0,1)**3
hm=D.max(1)[:,None]
def fit(y,W):
    S0=W.sum(1);S1=(W*(x[None]-x[:,None])).sum(1);S2=(W*(x[None]-x[:,None])**2).sum(1);T0=(W*y).sum(1);T1=(W*y*(x[None]-x[:,None])).sum(1)
    return (S2*T0-S1*T1)/(S0*S2-S1*S1)
def model(y,K,c,its,rw):
    dl=np.ones(90)
    for it in range(its+1):
        f=fit(y,K*dl)
        dl=rw(y-f,c)
    return f
bis=lambda r,c:np.clip(1-(r/(c*np.median(abs(r))))**2,0,1)**2
def err(K,c,its,rw=bis): return np.sqrt(np.mean([(np.array([tm(mm(q)) for q in model(y,K,c,its,rw)[60:].reshape(5,6)])-t)**2 for y,t in zip(Y,T)]))
if __name__=='__main__':
    best=[]
    for nm,K in (('h',tri(hm)),('1.1',tri(hm*1.1)),('89',tri(89.)),('90',tri(90.)),('60',tri(60.)),('45',tri(45.)),('ones',np.ones((90,90))),('bi',np.clip(1-(D/hm)**2,0,1)**2)):
        for c in (2,3,4,4.685,5,6,7,8,9,12):
            for its in range(0,6): best.append((err(K,c,its),nm,c,its))
    best.sort(); print(best[:12])
