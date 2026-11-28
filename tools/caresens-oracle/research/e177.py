from sc import *
spec=dict(seed=5,readings=2030,start=1,lo=80,hi=200,knot_min=10,knot_max=60,noise=12)
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
i=int(np.nonzero(seq==2016)[0][0])
names=[n for n in co.STATE.names if 'diab' in n]
print({n:s[n][i] for n in names})
print({n:s[n][i+1] for n in names})
recs=co.records(seq,t,np.array(adc,'u2'),T)[i+1:i+2]
def level(n,hi,lo,mean,cv,**extra):
    st=s[i:i+1].copy()
    st['diabetes_cnt_idx']=n; st['diabetes_cnt_hi_glu']=hi; st['diabetes_cnt_low_glu']=lo
    st['diabetes_mean_x']=mean; st['diabetes_M2']=(cv/100*mean)**2*(n-1)
    for k,v in extra.items(): st[k]=v
    _,_,_,a,_=REF.run(d,recs,state=st)
    return int(a['diabetes_level_diabetes'][0]),float(a['diabetes_TAR'][0]),float(a['diabetes_TBR'][0]),float(a['diabetes_CV'][0])
print('case142',level(1078,97,41,121.8,30.25))
for cv in (19.9,20,25,29,29.9,30,30.1,31,33,35.9,36):
    print('cv',cv,[level(1000,hi,lo,125,cv)[0] for hi,lo in ((0,0),(49,0),(50,0),(90,0),(100,0),(149,0),(150,0),(90,40),(90,45),(90,49),(0,40),(0,49))])
