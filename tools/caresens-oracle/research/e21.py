from e20 import *
np.set_printoptions(linewidth=250,precision=5,suppress=True)
w=np.full((4,30),10000,'u2'); r,o,g,a,s=run(d,w,34.0,snap=True); st=s[2:3].copy(); base=g['tran_inA_1min'][3][0]
def f(st):
    r,o,g,a=run(d,w[3:],34.0,seq=[4],args=st.copy()); return g['tran_inA_1min'][0],a['prev_mov_median_curr'][0]
print('const',f(st)[0]-base)
for dl in (1e-9,1e-6,1e-3,1):
    q=st.copy(); q['prev_outlier_removed_curr'][0][59]+=dl; v,m=f(q); print(dl,(v-base)/dl,(m-base)/dl)
# two-valued alternating noise so that MAD is large and all |r| similar
rng=np.random.default_rng(3)
q0=st.copy(); n=rng.choice([-1,1],90); q0['prev_outlier_removed_curr'][0]+=n[:60]*.1
b=f(q0)[0]
