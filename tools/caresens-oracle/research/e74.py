import numpy as np
np.set_printoptions(precision=6,suppress=True,linewidth=180)
W=np.load('dt2.npy').astype(float)
m2=W[:,2:].mean(1);SS=((W[:,2:]-m2[:,None])**2).sum(1);a=W[:,0]-m2;b=W[:,1]-m2
X=np.c_[a*a,b*b,a*b]/SS[:,None]; c,r,_,_=np.linalg.lstsq(X,np.ones(len(W)),rcond=None); print(c,np.abs(X@c-1).max())
def reg(y,cols,n):
    X=np.c_[tuple(cols)]; c,_,_,_=np.linalg.lstsq(X,y,rcond=None); print(n,c,np.abs(X@c-y).max()/np.abs(y).max())
m=W.mean(1); z=(W-m[:,None])/W.std(1,ddof=1)[:,None]
reg(np.ones(len(W)),[z[:,0]**2,z[:,1]**2,z[:,0]*z[:,1]],'z')
print(np.sort(X@c)[:5],np.sort(X@c)[-5:])
f=((W[:,0]-m)**2+(W[:,1]-m)**2)/((W-m[:,None])**2).sum(1); print('frac',f.min(),f.max())
# regress against fit on index
u=np.arange(30.)
for nm,v in (('absdev frac',(np.abs(W[:,:2]-m[:,None]).sum(1))/np.abs(W-m[:,None]).sum(1)),('sd2/sd',W[:,2:].std(1,ddof=1)/W.std(1,ddof=1)),('sd2/sd ddof0',W[:,2:].std(1)/W.std(1)),('cvratio',(W[:,2:].std(1,ddof=1)/m2)/(W.std(1,ddof=1)/m)),('cv diff',(W.std(1,ddof=1)/m)-(W[:,2:].std(1,ddof=1)/m2))): print(nm,v.min(),v.max())
