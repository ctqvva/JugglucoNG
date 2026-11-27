exec(open('e200.py').read().split("f=[n")[0])
for i in list(range(5,20))+[2000,2001,2002]:
    print(i,'seq',seq[i],'res',round(float(o['result_glucose'][i]),3),'smooth',np.round(o['smooth_result_glucose'][i],2),'plasma',g['err16_CGM_plasma'][i],'isf',g['err16_CGM_ISF_smooth'][i],'init',round(float(g['init_cg'][i]),2),'resc',round(float(g['out_rescale'][i]),2))
