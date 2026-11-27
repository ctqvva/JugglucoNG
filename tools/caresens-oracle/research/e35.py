from e31 import *
from e33 import sm
rr=np.random.default_rng(9);F=[];T=[]
for k in range(60):
    n=rr.normal(0,.1,60)+.3*np.sin(np.arange(60)/rr.uniform(4,20)); q=st.copy(); q['prev_outlier_removed_curr'][0]=base+n
    _,o,g3,a=run(d,w[3:],34.0,seq=[4],args=q.copy()); F.append(sm(np.append(n,np.zeros(30)))[60:]); T.append(g3['tran_inA_1min'][0]-base)
F=np.array(F);T=np.array(T)
for k in range(5):
    b=6*k; u=F[:,b+2:b+3]; X=np.c_[F[:,b:b+6]]; 
    y=T[:,k]-F[:,b+2]
    X=np.c_[(F[:,b+3]-F[:,b+1])/2,F[:,b+3]-2*F[:,b+2]+F[:,b+1],F[:,b+4]-3*F[:,b+3]+3*F[:,b+2]-F[:,b+1]]
    mono=np.all(np.diff(F[:,b:b+6])>0,1)|np.all(np.diff(F[:,b:b+6])<0,1)
    c,res,_,_=np.linalg.lstsq(X[mono],y[mono],rcond=None); print(k,mono.sum(),c,np.abs(X[mono]@c-y[mono]).max())
