import numpy as np
from scipy.interpolate import CubicSpline
obs=np.array([.162731,-1.041499,.807770,.070989])
def test(nd,vals,bc='natural'):
    out=[]
    for i in range(4):
        e=np.eye(4)[i]; y=[sum(e[k] for k in v) if isinstance(v,tuple) else e[v] for v in vals]
        out.append(CubicSpline(nd,y,bc_type=bc)(-50,1)*100)
    return np.array(out)
C=[]
for nd,vals in (([-350,-250,-150,-50,350],[0,0,1,2,3]),([-300,-250,-150,-50,350],[0,0,1,2,3]),([-290,-250,-150,-50,350],[0,0,1,2,3]),([-250,-150,-50,350],[0,1,2,3]),([-250,-200,-150,-100,-50,350],[0,0,1,1,2,3])):
    for bc in ('natural','clamped','not-a-knot',((1,0),(2,0)),((2,0),(1,0))):
        try: r=test(nd,vals,bc); C.append((np.abs(r-obs).max(),nd,bc,r))
        except Exception as ex: pass
C.sort(key=lambda z:z[0])
for c in C[:6]: print(c)
