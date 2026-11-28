from e16 import *
np.set_printoptions(linewidth=250,precision=4,suppress=True)
d=dev(); rng=np.random.default_rng(0)
wo=np.tile(10000+np.array([0,6,2,8,4,10]*5),(6,1)).astype('u2')
r,o,g,a,s=run(d,wo,34.0,snap=True)
st=s[2:3].copy()  # state after 3 readings
def f(st,w=wo[3]):
    r,o,g,a=run(d,w[None],34.0,seq=[4],args=st.copy()); return g['tran_inA_1min'][0],a['prev_mov_median_curr'][0]
b,bm=f(st); print(b,bm, 'times',st['time_10sec_arr'][0][[0,1,29,30,31,59,60,89]]-BASE)
J=[]
for i in range(60):
    q=st.copy(); q['prev_outlier_removed_curr'][0][i]+=1e-4; v,m=f(q); J.append(np.append(v-b,m-bm)/1e-4)
J=np.array(J)
for k in range(8): print(k,J[:,k])
print(J.sum(0))
