exec(open('e200.py').read().split("f=[n")[0])
E=s['err_glu_arr']; R=o['result_glucose']
print(co.STATE['err_glu_arr'].base if hasattr(co.STATE['err_glu_arr'],'base') else '', co.STATE.fields['err_glu_arr'][0])
for i in (5,11,12,2000):
    print(i,'tail',E[i][-6:],'R',np.round(R[i-5:i+1],3),'P',g['err16_CGM_plasma'][i])
i=2000
print('time_standard_arr tail',s['time_standard_arr'][i][-4:]-t[i], 'curr_avg tail', s['curr_avg_arr'][i][-3:])
