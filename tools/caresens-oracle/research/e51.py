from gen import *
np.set_printoptions(linewidth=220,precision=8,suppress=True)
for kw in (dict(),dict(slope100=5.0),dict(eapp=.05),dict(eapp=.05,slope100=5.,vref=1.45)):
  for seed,lo,hi in((1,40,400),(2,30,200)):
    N=460; d=dev(**kw); T=34+1.*np.sin(np.arange(N)/37); wo=mkwo(N,seed,lo,hi,d=d,T=T); r,o,g,a,s=run(d,wo,T,snap=True)
    cg=g['init_cg']; kk=g['out_rescale']-cg; sq=np.arange(1,N+1)
    m=(sq<=288)&(np.abs(kk)>1e-9)
    f=np.polyfit(cg[m],kk[m],2); print(kw,seed,f,'res',np.abs(np.polyval(f,cg[m])-kk[m]).max(),'roots',np.roots(f),'seqrange',sq[m].min(),'cg range',cg[m].min(),cg[m].max(), 'errs',np.unique(o['errcode']))
    z=(sq<=288)&(np.abs(kk)<=1e-9); print('   zero cases min cg',cg[z].min(), 'seqs<180 zero',sq[z&(cg<179.9)][:20], 'ratio x/cg',repr((s['biasIIR'][:,1]/cg)[100]))
