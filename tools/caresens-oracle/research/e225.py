from sc import *
import json
sv=json.load(open('err_survey.json'))
for x in [x for x in sv if x['16']]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec'],True)
    C=g['err16_condi']; e=(o['errcode']&16)!=0
    any_=C.any(1)
    tr=np.nonzero(np.diff(C.astype(int),axis=0).any(1))[0]+1
    print(x['name'],'cond on count',C.sum(0).tolist(),'err count',e.sum(),'transitions',[(int(i),C[i].tolist()) for i in tr[:8]], 'result_prev at end',s['err16_result_prev'][-1])
