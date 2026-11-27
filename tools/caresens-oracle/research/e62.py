from gen import *
np.set_printoptions(linewidth=220,precision=5,suppress=True)
KS=np.array([-1.6,2.7,6.6,5.6,5.4,3.9,-1.6])/21
def smooth(old,new,first):
    b=np.append(old[1:],new); x=np.append(b,[b[-1]]*3); out=b.copy()
    for i in range(3,10):
        out[i]=(KS*x[i-3:i+4]).sum() if i<9 else b[9]
    return out
N=60; wo=mkwo(800,1,60,200); r,o,g,a,s=run(dev(),wo[:N],34.,snap=True)
for i in range(1,N):
    old=s['smooth_sig_in'][i-1]; m=smooth(old,o['result_glucose'][i],0); e=m-s['smooth_sig_in'][i]
    b=np.append(old[1:],o['result_glucose'][i])
    if i<14 or i in(30,50): print(i+1,'resid',e,'| moved',np.nonzero(np.abs(b-s['smooth_sig_in'][i])>1e-9)[0], 'frep',s['smooth_f_rep_in'][i],o['smooth_fixed_flag'][i])
