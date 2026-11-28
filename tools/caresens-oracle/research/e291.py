import craft
from craft import *
for sl in (3.5226,7.0):
    d=co.default_device(); d['slope100'][0]=sl; craft.DEV=d
    prev=None
    for c in (10,15,20,25,28,30,32,35,40,50,60):
        o,g,s=run_plateau(float(c),40,n=600)
        cc=g['err2_crt_current'][530]; cg=g['err2_crt_glu'][530]; cd=g['err2_condi'][530]
        first=np.nonzero(o['errcode']&2)[0][:1].tolist()
        print('slope',sl,'c',c,'R %.0f'%o['result_glucose'][530],'crt_current',cc,'crt_glu',cg,'cv %.3f'%g['err2_crt_cv'][530],'condi',cd,'err2 first',first,'delay',g['err2_delay_condi'][530],'cummax %.2f'%g['err2_cummax'][530])
