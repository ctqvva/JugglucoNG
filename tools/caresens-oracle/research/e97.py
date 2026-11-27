import numpy as np
from scipy.interpolate import CubicSpline
from scipy.optimize import least_squares
obs=np.array([.1627,-1.04125,.80795,.07135])
def S(p,bc='natural'):
    h1,h2,h3=p; nd=[-50-h2-h1,-50-h2,-50,-50+h3]; out=[]
    for i in range(4):
        y=np.zeros(4);y[i]=1; out.append(CubicSpline(nd,y,bc_type=bc)(-50,1)*100)
    return np.array(out)
for bc in ('natural','not-a-knot',((1,0),(2,0))):
  for x0 in ([100,100,400],[50,100,400],[150,100,300]):
    r=least_squares(lambda p:S(p,bc)-obs,x0,bounds=([1,1,1],[2000,2000,2000])); print(bc,r.x,r.fun)
