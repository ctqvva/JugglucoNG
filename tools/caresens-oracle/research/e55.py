from gen import *
np.set_printoptions(linewidth=220,precision=10,suppress=True)
N=2000; wo=mkwo(N,1,40,300); r,o,g,a,s=run(dev(),wo,34.,snap=True)
K=s['out_kalman'];z=g['out_rescale']
def fit(i0,i1):
    X=np.c_[K[i0-1:i1-1],z[i0:i1]]; Y=K[i0:i1]; c,_,_,_=np.linalg.lstsq(X,Y,rcond=None); return c.T,np.abs(X@c-Y).max()
for a_,b_ in((9,30),(30,200),(300,600),(600,1990),(9,1990)):
    c,e=fit(a_,b_); print(a_,b_,'err',e); print(c)
M,G=c[:,:3],c[:,3]; print('eig',np.linalg.eigvals(M))
w=(z-g['opcal_ad'])/(z-K[:,1]); print('weights',w[9:40]*24, np.abs(g['opcal_ad']-K[:,1])[40:].max(), g['opcal_ad'][40]-K[40,1])
