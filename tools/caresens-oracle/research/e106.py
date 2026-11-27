from gen import *
np.set_printoptions(linewidth=200,precision=4,suppress=True)
def tr(S,lo,hi,idx):
    v=S[idx]
    if (v<lo).any() or (v>hi).any(): return 100.
    m=(S[9]-S[3])/30
    if abs(m)>1 and np.sign(S[9]-S[8])!=np.sign(m): return 0.
    return m
for idx in (np.arange(3,10),np.array([3,9]),np.arange(0,10),np.array([3,8,9])):
  for lo,hi in ((40,500),(40,400),(39,501)):
    bad=[]
    for seed in range(8):
        wo=mkwo(3000,seed,20,520,noise=[12,60,150][seed%3]); r,o,g,a,s=run(dev(),wo,34.,snap=True); S=s['smooth_sig_in']
        for i in range(11,3000):
            p_=tr(S[i],lo,hi,idx)
            if abs(p_-o['trendrate'][i])>1e-9: bad.append((i,o['trendrate'][i],p_,S[i].round(1),o['errcode'][i]))
    print(idx,lo,hi,len(bad)); 
    for b in bad[:3]: print('  ',b)
