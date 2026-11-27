from gen import *
np.set_printoptions(linewidth=220,precision=6,suppress=True)
N=4320; wo=mkwo(N,1); r,o,g,a=run(dev(),wo,34.)
from fractions import Fraction
inv=(g['out_iir']/g['out_drift']); c=np.polyfit(np.arange(1,1501.)/1000,inv[:1500],3); print(['%.12g'%(x/1000**(3-i)) for i,x in enumerate(c)])
dr=g['out_drift']; b=g['curr_baseline']
for w in (12,24,36,48,72,144,288): 
    m=np.array([dr[max(0,i-w+1):i+1].mean() for i in range(N)]); print('win',w,np.abs(m-b)[:700].max(), np.abs(m-b)[:w].max())
k=g['out_rescale']/g['init_cg']-1; nz=np.nonzero(np.abs(k)>1e-12)[0]; runs=np.split(nz,np.nonzero(np.diff(nz)>1)[0]+1); print([(q[0]+1,q[-1]+1) for q in runs])
for i in list(range(17,53,3))+list(range(89,180,10))+list(range(200,400,12)): 
    if abs(k[i])>1e-12: print(i+1,'k %.6f'%k[i],'x=1-d/b %.5f'%(1-dr[i]/b[i]),'(d-b) %.4f'%(dr[i]-b[i]),'k/x %.5f'%(k[i]/(1-dr[i]/b[i])),'k*d %.5f'%(k[i]*dr[i]),'k*d/(b-d) %.6f'%(k[i]*dr[i]/(b[i]-dr[i])), 'b',round(b[i],4), 'd',round(dr[i],4))
