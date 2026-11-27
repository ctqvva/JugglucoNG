from gen import *
N=2100; dec=np.linspace(1,.5,4320)[:N,None]; w=mkwo(4320,5,40,300)[:N]; W=(2756+(w-2756.)*dec).clip(0,65535).astype('u2')
r,o,g,a,s=run(dev(),W,34.,snap=True); G=o['result_glucose']
for k in range(1228,1240): print(k+1,'G-1 %.3f'%G[k-1],'n',s['diabetes_cnt_idx'][k],'lo',s['diabetes_cnt_low_glu'][k],'mean %.4f'%s['diabetes_mean_x'][k],'dt',o['data_type'][k-1],o['errcode'][k-1])
m=s['diabetes_mean_x']; n=s['diabetes_cnt_idx'].astype(float)
for k in (2014,2015,2016,2017): print(k+1,m[k],n[k],s['diabetes_M2'][k], (m[k]*n[k]-m[k-1]*n[k-1]) , G[k-1],G[k-2])
