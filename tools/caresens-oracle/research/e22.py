from e20 import *
np.set_printoptions(linewidth=230,precision=5,suppress=True)
# current 30 constant; previous 60 = +-delta pattern with point i zero
w=np.full((4,30),10000,'u2'); r,o,g,a,s=run(d:=dev(),w,34.0,snap=True); st=s[2:3].copy(); base=g['tran_inA_1min'][3][0]
def f(st):
    r,o,g,a=run(d,w[3:],34.0,seq=[4],args=st.copy()); return np.append(g['tran_inA_1min'][0],a['prev_mov_median_curr'][0])
rng=np.random.default_rng(3); dl=.1
acc=np.zeros((60,8));K=400
for k in range(K):
    n=rng.choice([-1,1],60)*dl*rng.uniform(.9,1.1,60)
    for i in range(0,60):
        if i%4!=k%4: continue
        m=n.copy(); m[i]=1e-5; q=st.copy(); q['prev_outlier_removed_curr'][0]=base+m; A_=f(q)
        m[i]=-1e-5; q['prev_outlier_removed_curr'][0]=base+m; acc[i]+=(A_-f(q))/2e-5
acc/=K/4
np.save('kern.npy',acc)
for c in (0,2,4,7): print(c,acc[:,c])
