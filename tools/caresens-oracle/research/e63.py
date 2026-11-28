from gen import *
np.set_printoptions(linewidth=220,precision=6,suppress=True)
N=800; wo=mkwo(N,1,60,200); r,o,g,a,s=run(dev(),wo,34.,snap=True)
t=o['trendrate'];S=s['smooth_sig_in']; res=o['result_glucose']
v=np.arange(40,800)
x=np.arange(10.)*5
for nm,B in(('sm',S),('raw',np.array([res[i-9:i+1] if i>=9 else np.zeros(10) for i in range(N)]))):
  for a_ in range(0,9):
    for b_ in range(a_+2,11):
        sl=np.array([np.polyfit(x[a_:b_],B[i][a_:b_],1)[0] for i in v]); e=np.abs(sl-t[v]); 
        if np.median(e)<.012: print(nm,a_,b_,np.median(e),e.max(),(e<1e-9).mean())
X=S[v]; c,_,_,_=np.linalg.lstsq(X,t[v],rcond=None); print('lin sm',c*5,np.abs(X@c-t[v]).max())
X=np.array([res[i-14:i+1] for i in v]); c,_,_,_=np.linalg.lstsq(X,t[v],rcond=None); print('lin raw',c*5,np.abs(X@c-t[v]).max())
