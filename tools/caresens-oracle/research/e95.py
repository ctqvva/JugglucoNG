import numpy as np
from scipy.interpolate import CubicSpline
obs_s=np.array([.1627,-1.04125,.80795,.07135]); obs_c=np.array([-.122,.78087,-.79348,.13388])
for nd in ([-250,-150,-50,350],[-245,-145,-45,355],[-255,-155,-55,345],[-250,-150,-50,355],[-200,-125,-50,350]):
  for bc in ('natural',((2,0),(2,0)),((1,0),(2,0)),'not-a-knot'):
    S=[];C=[]
    for i in range(4):
        y=np.zeros(4);y[i]=1; cs=CubicSpline(nd,y,bc_type=bc); S.append(cs(-50,1)*100);C.append(cs(-50,2)*1e4)
    print(nd,bc,np.round(S,5),np.round(C,5))
print('obs',obs_s,obs_c)
