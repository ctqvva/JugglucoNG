import ref,io,contextlib
from ref import *
from gen import mkwo
np.seterr(all='ignore')
for sp in (35,36,37,38,40,45,50):
    ref.TRSPAN=sp; rng=np.random.default_rng(0); out=[]
    for k in range(4):
        N=1500; sq=np.cumsum(rng.choice([1]*30+[2,3,5,6,7,40],N)); t=BASE+300*sq+rng.integers(-[0,3,20,40][k],[0,3,20,40][k]+1,N)
        f=io.StringIO()
        with contextlib.redirect_stdout(f): compare('g',mkwo(N,k,noise=[12,50,100,30][k]),seq=sq,t=t)
        out.append(f.getvalue().split("'tr': ")[1].split(',')[0])
    print(sp,out)
