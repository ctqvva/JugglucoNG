from lab import *
np.set_printoptions(linewidth=190,precision=8,suppress=True)
def mm(x):
    n=len(x); return np.array([np.median(x[max(0,i-3):min(n,i+3)]) for i in range(n)])
def tm(x): return (np.sum(x)-np.min(x)-np.max(x))/(len(x)-2)
rng=np.random.default_rng(2)
N=40
for name,wo in [('noise',rng.normal(10000,30,(N,30))),('ramp',10000+np.arange(N*30).reshape(N,30)*8+rng.normal(0,20,(N,30)))]:
    wo=wo.astype('u2'); d=dev()
    r,o,g,a,s=run(d,wo,34.0,snap=True)
    for i in (0,1,2,3,10):
        q=s['prev_outlier_removed_curr'][i][30:]; x=g['tran_inA'][i]
        print(name,i,'changed',[(j,x[j],q[j]) for j in range(30) if q[j]!=x[j]])
        print(' lib ',g['tran_inA_1min'][i]); print(' mine',np.array([tm(mm(z)) for z in q.reshape(5,6)]),'raw',np.array([tm(mm(z)) for z in x.reshape(5,6)]), 'med',s['prev_mov_median_curr'][i], mm(q[24:])[3:], s['outlier_max_index'][i])
        t1=g['tran_inA_1min'][i]; print(' 5min',g['tran_inA_5min'][i],tm(t1))
