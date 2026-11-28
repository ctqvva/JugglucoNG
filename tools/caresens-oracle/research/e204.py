exec(open('e200.py').read().split("f=[n")[0])
for i in (10,11,12,13,50):
    print(i,'seq',seq[i],'t',t[i]-t[0])
    print('  plasma36',s['err16_CGM_plasma'][i][-8:])
    print('  dt36',s['err16_dt_arr'][i][-8:], 'time5_first',s['err16_time5_first'][i])
    print('  isf865',s['err16_CGM_ISF_smooth'][i][-6:], 'dbg P',g['err16_CGM_plasma'][i],'I',g['err16_CGM_ISF_smooth'][i])
    print('  res hist', np.round(o['smooth_result_glucose'][i],2), 'result',round(float(o['result_glucose'][i]),2))
