import numpy as np
from scipy.interpolate import CubicSpline
from scipy.optimize import least_squares
v=np.array([20656,20807,20969,21142,21325,21519,21722,21934,22156,22386,22625,22872,23127,23388,23657,23933,24215,24503,24797,25096,25400,25709,26023,26340,26661,26985,27313,27643,27975,28309])-20000.
t=np.arange(10,301,10)
for bc in ('natural','clamped','not-a-knot'):
    f=lambda p:CubicSpline([p[0]-p[1]-p[3],p[0]-p[1],p[0],p[2]],[0,0,0,10000.],bc_type=bc)(t)-v
    for x0 in ([-45,100,355,100],[-5,100,400,100],[-100,50,350,50]):
        try: r=least_squares(f,x0); print(bc,r.x,np.abs(r.fun).max())
        except Exception as e: print(bc,e)
c=np.polyfit(t,v,3); print(c,np.roots(c)); print('curv zero',-c[1]/3/c[0],'vals',np.polyval(c,[-50,-45,-40,0,300,350,355,360,400]), np.polyval(np.polyder(c),[-50,-45,355,350,0,300]))
