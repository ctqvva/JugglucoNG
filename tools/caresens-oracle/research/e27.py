from e24 import *
from scipy.optimize import minimize
w=np.full((4,30),10000,'u2'); _,o,g2,a,s2=run(d,w,34.0,snap=True); st=s2[2:3].copy(); base=g2['tran_inA_1min'][3][0]
rr=np.random.default_rng(5);DS=[]
for k in range(12):
    n=rr.normal(0,.1,60); q=st.copy(); q['prev_outlier_removed_curr'][0]=base+n
    _,o,g3,a=run(d,w[3:],34.0,seq=[4],args=q.copy()); DS.append((np.append(n,np.zeros(30)),np.append(g3['tran_inA_1min'][0],a['prev_mov_median_curr'][0])-base))
def pred(f): return np.append([tm(mm(z)) for z in f[60:].reshape(5,6)],mm(f[84:])[3:])
def E(p,its):
    a,b,h,c=p; K=np.clip(1-(D/(hm*h))**a,0,1)**b
    return np.sqrt(np.mean([(pred(model(y,K,c,its,bis))-t)**2 for y,t in DS]))
if __name__=='__main__':
    for its in range(1,5):
        r=minimize(E,[3,3,1.001,6],args=(its,),method='Nelder-Mead',options=dict(maxiter=400)); print(its,E([3,3,1,6],its),r.x,r.fun)
