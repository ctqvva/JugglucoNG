from gen import *
N=2100; W=mkwo(N,3,300,700)
r,o,g,a,s=run(dev(),W,34.,snap=True); G=o['result_glucose']; m=s['diabetes_mean_x']; n=s['diabetes_cnt_idx'].astype(int)
inc=[(k+1,round(G[k-1],1),n[k]-n[k-1],s['diabetes_cnt_hi_glu'][k]-s['diabetes_cnt_hi_glu'][k-1]) for k in range(864,2016)]
added=[z[1] for z in inc if z[2]]; skipped=[z[1] for z in inc if not z[2]]; print('added range',min(added),max(added),'skipped range',min(skipped) if skipped else None,max(skipped) if skipped else None,len(skipped))
hiadd=[z[1] for z in inc if z[3]]; nohi=[z[1] for z in inc if z[2] and not z[3]]; print('hi counted min',min(hiadd),'not hi max',max(nohi))
print(inc[:6])
