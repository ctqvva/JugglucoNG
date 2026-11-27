from gen import *
np.set_printoptions(linewidth=230,precision=5,suppress=True)
N=2100; w=mkwo(N,1,40,300)
r,o,g,a,s=run(dev(),w,34.,snap=True)
G=o['result_glucose']
for k in (864,865,866,1000,2015,2016,2017):
    print(k+1,'mean_x',s['diabetes_mean_x'][k],'M2',s['diabetes_M2'][k],'hi',s['diabetes_cnt_hi_glu'][k],'lo',s['diabetes_cnt_low_glu'][k],'idx',s['diabetes_cnt_idx'][k],'lvl',s['diabetes_level_diabetes'][k],'TAR',s['diabetes_TAR'][k],s['diabetes_TBR'][k],s['diabetes_CV'][k])
for a_ in (864,865,866): 
    for b_ in (2015,2016,2017):
        z=G[a_:b_]; print(a_,b_,len(z),z.mean(),(z>180).mean()*100,(z>250).mean()*100,(z<70).mean()*100,(z<54).mean()*100,z.std()/z.mean()*100, z.std(ddof=1)/z.mean()*100)
print('====')
for nm,v in (('glu',G),('resc',g['out_rescale']),('cg',g['init_cg']),('ad',g['opcal_ad']),('S0',s['smooth_sig_in'][:,0]),('S3',s['smooth_sig_in'][:,3]),('S9',s['smooth_sig_in'][:,9]),('so',o['smooth_result_glucose'][:,0])):
    for a_,b_ in ((864,2015),(865,2016),(863,2014),(855,2006),(858,2009)):
        z=v[a_:b_]
        if abs(z.mean()-182.13574450119225)<1e-6 or nm in('glu','resc'): print(nm,a_,b_,z.mean(),(z>180).sum(),(z<70).sum(),((z-z.mean())**2).sum())
print('####')
m=s['diabetes_mean_x']; n=s['diabetes_cnt_idx'].astype(float)
k=np.arange(900,1000); add=m[k]*n[k]-m[k-1]*n[k-1]
S=s['smooth_sig_in']
for nm,v in [('glu',G),('resc',g['out_rescale']),('cg',g['init_cg'])]+[('S%d'%j,S[:,j]) for j in range(10)]:
    for lag in range(-8,3):
        e=np.abs(add-v[k+lag]).max()
        if e<1e-6: print(nm,lag,e)
print(add[:4],G[k[:4]],S[k[0],:])
