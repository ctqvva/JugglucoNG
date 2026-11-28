exec(open('e200.py').read().split("f=[n")[0])
recs=co.records(seq,t,np.array(adc,'u2'),T)
i=2000
base=s[i-1:i].copy()
print('sig_in',base['smooth_sig_in'][0],'time',base['smooth_time_in'][0]-base['smooth_time_in'][0][-1])
def run1(st):
    c_,o_,g_,a_,_=REF.run(d,recs[i:i+1],state=st)
    return g_[0,0]['err16_CGM_plasma'],o_[0,0]['smooth_result_glucose'],o_[0,0]['result_glucose']
st=base.copy(); st['smooth_sig_in']=150.0
p0,sm0,r0=run1(st); print('base',p0,sm0,r0)
for j in range(10):
    st=base.copy(); st['smooth_sig_in']=150.0; st['smooth_sig_in'][0][j]=150.0+210
    p,sm,r=run1(st); print(j,'P',p,'dP/210*21',(p-p0)/210*21,'sm',np.round(sm,2))
