from sc import *
spec=dict(seed=5,readings=2030,start=1,lo=80,hi=200,knot_min=10,knot_max=60,noise=12)
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
i=int(np.nonzero(seq==2016)[0][0])
names=[n for n in co.STATE.names if 'diab' in n]
recs=co.records(seq,t,np.array(adc,'u2'),T)[i+1:i+2]
def level(n,hi,lo,mean,cv,**extra):
    st=s[i:i+1].copy()
    st['diabetes_cnt_idx']=n; st['diabetes_cnt_hi_glu']=hi; st['diabetes_cnt_low_glu']=lo
    st['diabetes_mean_x']=mean; st['diabetes_M2']=(cv/100*mean)**2*(n-1)
    for k,v in extra.items(): st[k]=v
    _,_,_,a,_=REF.run(d,recs,state=st)
    return int(a['diabetes_level_diabetes'][0]),float(a['diabetes_TAR'][0]),float(a['diabetes_TBR'][0]),float(a['diabetes_CV'][0])
