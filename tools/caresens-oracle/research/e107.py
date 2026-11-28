from gen import *
Z=[];K=[]
for seed in range(16):
    wo=mkwo(3000,seed,40,480,noise=[12,60,150,300][seed%4]); r,o,g,a,s=run(dev(),wo,34.,snap=True); S=s['smooth_sig_in']
    for i in range(11,3000):
        v=S[i,3:]
        if (v<40).any() or (v>500).any(): continue
        m=(S[i,9]-S[i,3])/30; l=(S[i,9]-S[i,8])/5; t=o['trendrate'][i]
        if abs(m)>1 and np.sign(l)!=np.sign(m): (Z if t==0 else K).append((l,m,t))
Z=np.array(Z);K=np.array(K); print(len(Z),len(K),'zero max|l|',np.abs(Z[:,0]).max(),'kept min|l|',np.abs(K[:,0]).min() if len(K) else None, 'kept==l',np.abs(K[:,0]-K[:,2]).max() if len(K) else None)
