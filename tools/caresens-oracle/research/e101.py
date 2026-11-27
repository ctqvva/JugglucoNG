import numpy as np
from scipy.interpolate import CubicSpline
from scipy.optimize import least_squares
obs=np.array([.162731,-1.041499,.807770,.070989])
def S(p,bc):
    h1,h2=p; nd=[-50-h2-h1,-50-h2,-50,350]
    return np.array([CubicSpline(nd,np.eye(4)[i],bc_type=bc)(-50,1)*100 for i in range(4)])
for bc in ('natural',((1,0),(2,0)),((2,0),(2,0))):
    best=min((least_squares(lambda p:S(p,bc)-obs,x0,bounds=([1,1],[5000,5000])) for x0 in ([100,100],[300,100],[50,200],[1000,300])),key=lambda r:r.cost)
    print(bc,best.x,best.fun)
# maybe 5 nodes incl cur second group? or prev groups of different size -> test generic: natural spline nodes (-250,-150,-50,350) but y-values are not the block means
