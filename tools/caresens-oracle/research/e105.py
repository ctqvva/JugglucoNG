from gen import *
np.set_printoptions(linewidth=200,precision=4,suppress=True)
R=[]
for seed in range(8):
    wo=mkwo(3000,seed,20,520,noise=[12,60,150][seed%3]); r,o,g,a,s=run(dev(),wo,34.,snap=True)
    S=s['smooth_sig_in']; m=(S[:,9]-S[:,3])/30; t=o['trendrate']
    for i in range(11,3000): R.append((t[i],m[i],S[i,9],S[i,8],S[i,3],o['result_glucose'][i],o['errcode'][i],g['cal_available_flag'][i]))
R=np.array(R)
c100=R[R[:,0]==100]; ok=R[np.abs(R[:,0]-R[:,1])<1e-9]; z=R[(R[:,0]==0)&(R[:,1]!=0)]
print(len(R),len(c100),len(ok),len(z),'other',len(R)-len(c100)-len(ok)-len(z))
print('100: S9 range',c100[:,2].min(),c100[:,2].max(),' ok S9 range',ok[:,2].min(),ok[:,2].max(), 'ok S3 range',ok[:,4].min(),ok[:,4].max())
lo=c100[c100[:,2]<200]; hi=c100[c100[:,2]>200]; print('100 lo: max S9',lo[:,2].max(),'min S3',lo[:,4].min(), ' hi: min S9',hi[:,2].min() if len(hi) else None)
print('ok near bounds: S9 min',ok[:,2].min(),'S9 max',ok[:,2].max(),' min(S) over 3..9?')
d=z[:,2]-z[:,3]; print('zero cases: m*lastdiff',np.c_[z[:8,1],d[:8]], 'min |m|',np.abs(z[:,1]).min())
okd=ok[:,2]-ok[:,3]; opp=(np.sign(okd)!=np.sign(ok[:,1])); print('ok with opposite sign: max |m|',np.abs(ok[opp,1]).max() if opp.any() else None)
