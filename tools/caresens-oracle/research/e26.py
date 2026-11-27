from e24 import *
K=tri(hm)
Yr=[np.append(s['prev_outlier_removed_curr'][i-1],g['tran_inA'][i]) for i in range(3,14)]
def E(Y,K,c,its,pre=lambda y:y,post=mm): return np.sqrt(np.mean([(np.array([tm(post(q)) for q in model(pre(y),K,c,its,bis)[60:].reshape(5,6)])-t)**2 for y,t in zip(Y,T)]))
print('raw',E(Yr,K,6,2),E(Yr,K,6,5))
gm=lambda y:np.concatenate([mm(q) for q in y.reshape(-1,6)])
print('mm first',E(Y,K,6,2,gm),E(Y,K,6,5,gm), 'all-mm',E(Y,K,6,2,mm))
def pm(y): return np.append(y[:60],gm(y[60:]))
print(E(Y,K,6,2,pm),E(Y,K,6,2,lambda y:np.append(y[:60],mm(y[60:]))))
# constrained: full quiet current, only prev noise: does model match?
w=np.full((4,30),10000,'u2'); _,o,g2,a,s2=run(d,w,34.0,snap=True); st=s2[2:3].copy(); base=g2['tran_inA_1min'][3][0]
rr=np.random.default_rng(5); n=rr.normal(0,.1,60); q=st.copy(); q['prev_outlier_removed_curr'][0]=base+n
_,o,g3,a=run(d,w[3:],34.0,seq=[4],args=q.copy()); y=np.append(base+n,np.full(30,base))
for its in range(6): print(its,(np.array([tm(mm(z)) for z in model(y,K,6,its,bis)[60:].reshape(5,6)])-g3['tran_inA_1min'][0])*1e5)
