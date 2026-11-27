from sc import *
import json
sv={x['name']:x for x in json.load(open('err_survey.json'))}
import make_fixtures as mf
for name,i in (('holdout-fast-swings',1988),('rand4',4207),('rand5',1245),('rand22',1769)):
    spec=sv[name]['spec']
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    for j in range(i-3,i+3):
        print(name,j,'seq',seq[j],'dt',t[j]-t[j-1],'R %.2f'%o['result_glucose'][j],'type',o['data_type'][j],'D3',g['err4_delay_condi'][j][3],'P',g['err16_CGM_plasma'][j],'init %.2f'%g['init_cg'][j],'smooth',np.round(o['smooth_result_glucose'][j],1),'err',o['errcode'][j])
