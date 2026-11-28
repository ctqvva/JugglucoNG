import ref,io,contextlib
from ref import *
from gen import mkwo
np.seterr(all='ignore')
for nm,f in (('maxd<600',lambda s:np.diff(s.St).max()<600),('maxd<=600',lambda s:np.diff(s.St).max()<=600),('maxd<450',lambda s:np.diff(s.St).max()<450),('<3000',lambda s:s.St[9]-s.St[0]<3000),('<=2850',lambda s:s.St[9]-s.St[0]<=2850),('<2950',lambda s:s.St[9]-s.St[0]<2950),('<=3000',lambda s:s.St[9]-s.St[0]<=3000)):
    ref.SMG=f; rng=np.random.default_rng(0); out=[]
    for k in range(5):
        N=1500; sq=np.cumsum(rng.choice([1]*30+[2,3,5,6,7,40],N)); j=[0,3,20,40,140][k]; t=BASE+300*sq+rng.integers(-j,j+1,N)
        ff=io.StringIO()
        with contextlib.redirect_stdout(ff): compare('g',mkwo(N,k,noise=[12,50,100,30,30][k]),seq=sq,t=t)
        v=ff.getvalue(); out.append((v.split("'S': ")[1].split(',')[0],v.split("'tr': ")[1].split(',')[0]))
    print(nm,out)
