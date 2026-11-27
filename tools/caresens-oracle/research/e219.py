from sc import *
spec=dict(seed=3,readings=600,start=1,slope100=4.0,temp_mode='const',temp_lo=3400,temp_hi=3400,lo=60,hi=300)
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
I=g['err16_CGM_ISF_smooth']; m=np.nonzero(~np.isnan(I))[0]; m=m[m>20]
M=g['tran_inA_1min']; X5=g['tran_inA_5min']; ti=g['tran_inA']
for name,X in (('1min',M[m]),('1min lag',np.concatenate([M[m],M[m-1],M[m-2]],1)),('5min lags',np.stack([X5[m-k] for k in range(12)],1)),('tran30',ti[m])):
    A=np.concatenate([X,np.ones((len(m),1))],1); cc,*_=np.linalg.lstsq(A,I[m],rcond=None)
    print(name,np.round(cc,3),'sd',np.std(I[m]-A@cc).round(3))
print(I[100:110], (X5[100:110]*25).round(2))
