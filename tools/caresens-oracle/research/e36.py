from e31 import *
from e33 import sm
np.set_printoptions(linewidth=200,precision=6,suppress=True)
# cleaned all ramp so that f is exact line (slope .01 per sample), p3 = continuing line positions
sl=.001; n=(np.arange(60)-60)*sl
def go(p3off,n=n):
    q=st.copy(); q['prev_outlier_removed_curr'][0]=base+n; q['prev_mov_median_curr'][0]=base+np.array(p3off)
    _,o,g3,a=run(d,w[3:],34.0,seq=[4],args=q); return g3['tran_inA_1min'][0]-base
f=sm(np.append(n,np.zeros(30)))[60:]; M=np.concatenate([mm(z) for z in f.reshape(5,6)])
b=go([0,0,0]); print(b,[tm(z) for z in M.reshape(5,6)])
for v in (-10,-1,-.01,-.001,-.0001,0,.0001,.001,.01,1):
    print(v,[ (go([v if i==j else 0 for i in range(3)])-b)[0] for j in range(3)], (go([v,v,v]))[:2])
print('M',M[:7])
