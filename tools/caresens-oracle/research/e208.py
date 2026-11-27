exec(open('e207.py').read().split("st=base.copy(); st['smooth_sig_in']=150.0")[0])

def runf(st):
    c_,o_,g_,a_,_=REF.run(d,recs[i:i+1],state=st.copy()); return g_[0,0]['err16_CGM_plasma'],g_[0,0]['err16_CGM_ISF_smooth'],o_[0,0]['result_glucose']
print('base',runf(base))
for j in (35,34,33,30,20,0):
    st=base.copy(); st['err16_CGM_plasma'][0][j]+=100; print('plasma36',j,runf(st))
st=base.copy(); st['err16_CGM_ISF_smooth'][0][-1]+=100; print('isf last',runf(st))
st=base.copy(); st['out_kalman'][0][:]+=50; print('kalman +50',runf(st))
for nm in [n for n in co.STATE.names if 'cal_result' in n or 'f_rep' in n]:
    st=base.copy(); 
    try: st[nm]=st[nm]+50
    except Exception as e: continue
    print(nm,runf(st))
