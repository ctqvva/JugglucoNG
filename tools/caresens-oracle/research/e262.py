from sc import *
import json
sv={x['name']:x for x in json.load(open('err_survey.json'))}
for name,i0 in (('rand4',409),('rand87',291),('holdout-fast-swings',1989)):
    seq,t,adc,T,c,o,g,s,d=runspec(sv[name]['spec'])
    for i in range(i0-3,i0+5): print(name,i,'seq',seq[i],'D',g['err4_delay_condi'][i].tolist(),'R %.2f raw %.3f 5min %.3f'%(o['result_glucose'][i],g['tran_inA'][i].max(),g['tran_inA_5min'][i]),'Kal x',np.round(g['out_rescale'][i],1))
