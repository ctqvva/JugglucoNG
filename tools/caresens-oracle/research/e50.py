from gen import *
np.set_printoptions(linewidth=220,precision=6,suppress=True)
N=600; wo=mkwo(4320,1)[:N]; r,o,g,a,s=run(dev(),wo,34.,snap=True)
x=s['biasIIR'][:,1];A=s['biasavg'][:,1]; cg=g['init_cg'];k=g['out_rescale']/cg-1; sq=np.arange(1,N+1)
print('bt-cg',np.abs(s['biastrend'][10:,1]-cg[10:]).max(),'ratio',repr((x/cg)[12]),repr((x/cg)[400]), 'first nz',np.argmax(x>0)+1)
av=np.cumsum(x)/np.maximum(sq-10,1); print('avg chk',np.abs(av-A)[:250].max(),np.abs(av-A)[:600].max(), np.argmax(np.abs(av-A)>1e-9)+1)
for i in (17,18,20,24,30,35,40,45,50,51,52,89,100,120,147,191,200,230,260,290,297,335,345,369,392,423,430):
    print(sq[i],'k %.6f x %.5f A %.5f'%(k[i],x[i],A[i]),'1-x/A %.5f'%(1-x[i]/A[i]),'k/(1-x/A) %.5f'%(k[i]/(1-x[i]/A[i])),'k/(A/x-1) %.5f'%(k[i]/(A[i]/x[i]-1)),'k/(A-x) %.5f'%(k[i]/(A[i]-x[i])),'k*x %.6f'%(k[i]*x[i]))
