from gen import *
import time
rng=np.random.default_rng(11); rows=[]
t0=time.time()
for tr in range(500):
    lo=rng.uniform(30,150); hi=lo+rng.uniform(5,350); w=mkwo(2018,tr,lo,hi,noise=10)
    r,o,g,a=run(dev(),w,34.)
    rows.append((float(a['diabetes_TAR'][0]),float(a['diabetes_TBR'][0]),float(a['diabetes_CV'][0]),float(a['diabetes_mean_x'][0]),int(a['diabetes_level_diabetes'][0]),int(a['diabetes_cnt_hi_glu'][0]),int(a['diabetes_cnt_low_glu'][0])))
np.save('lvl.npy',np.array(rows)); print(time.time()-t0)
R=np.array(rows)
from collections import Counter; print(Counter(R[:,4].astype(int)))
for l in sorted(set(R[:,4].astype(int))):
    q=R[R[:,4]==l]; print(l,'TAR',q[:,0].min().round(2),q[:,0].max().round(2),'TBR',q[:,1].min().round(2),q[:,1].max().round(2),'CV',q[:,2].min().round(2),q[:,2].max().round(2),'mean',q[:,3].min().round(1),q[:,3].max().round(1))
