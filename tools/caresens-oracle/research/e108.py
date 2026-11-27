from gen import *
np.set_printoptions(linewidth=200,precision=3,suppress=True)
Z=[];K=[]
for seed in range(16):
    wo=mkwo(3000,seed,40,480,noise=[12,60,150,300][seed%4]); r,o,g,a,s=run(dev(),wo,34.,snap=True); S=s['smooth_sig_in']
    for i in range(11,3000):
        v=S[i,3:]
        if (v<40).any() or (v>500).any(): continue
        m=(S[i,9]-S[i,3])/30; t=o['trendrate'][i]; d=np.diff(S[i])/5
        l=d[-1]
        if abs(m)>1 and np.sign(l)!=np.sign(m): (Z if t==0 else K).append(np.r_[m,d[2:]])
Z=np.array(Z);K=np.array(K)
print('kept'); print(K[:12]); print('zero'); print(Z[:12])
