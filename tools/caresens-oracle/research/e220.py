from sc import *
W=np.array([.19203765690376573,.1652246,.11844988,.07161009,.03473077,.01186997,.00419148,.01186997,.03473077,.07161009,.11844988,.1652246])
spec=dict(seed=3,readings=600,start=1,slope100=4.0,temp_mode='const',temp_lo=3400,temp_hi=3400,lo=60,hi=300)
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
I=g['err16_CGM_ISF_smooth']; P=g['err16_CGM_plasma']; m=np.arange(30,600)
X5=g['tran_inA_5min']; E=s['err_glu_arr']; R=o['result_glucose']
for nm,x in (('5min*25',X5*25),('5min*100/s',X5*100/float(d['slope100'][0])),('corr*25',g['corrected_re_current']*25)):
    v=np.array([sum(W[k]*x[i-k] for k in range(12)) for i in m])
    print(nm,'round match',np.mean(np.round(v)==I[m]),'mean diff',np.mean(I[m]-v).round(3),'sd',np.std(I[m]-v).round(3))
# check P with err_glu_arr
v=np.array([W[0]*R[i]+sum(W[k]*E[i-1][-k] for k in range(1,12)) for i in m]); print('P match',np.mean(np.round(v)==P[m]))
v=np.array([sum(W[k]*np.round(R[i-k]) for k in range(12)) for i in m]); print('P match all rounded',np.mean(np.round(v)==P[m]))
