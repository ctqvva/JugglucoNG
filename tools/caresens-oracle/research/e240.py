import craft
from craft import *
def state(adcv,dev):
    craft.DEV=dev
    o,g,s=run_plateau(0,12,shape=adcv)
    return g,o
def bisect(dev,pred,lo,hi):
    # pred true at lo (low adc), false at hi
    while hi-lo>1:
        m=(lo+hi)//2
        g,o=state(m,dev)
        if pred(g,o): lo=m
        else: hi=m
    g1,o1=state(lo,dev); g2,o2=state(hi,dev)
    return lo,(g1['tran_inA_5min'][508],o1['result_glucose'][508],g1['out_iir'][508]),(g2['tran_inA_5min'][508],o2['result_glucose'][508])
for sl in (3.5226,7.0,2.6):
    d=co.default_device(); d['slope100'][0]=sl
    for nm,p in (('C0',lambda g,o:g['err4_condi'][508][0]==1),('D0',lambda g,o:g['err4_delay_condi'][508][0]==1),('D3',lambda g,o:g['err4_delay_condi'][508][3]==1),('type4',lambda g,o:o['data_type'][508]==4)):
        print('slope',sl,nm,bisect(d,p,int(adc_for(-0.5)),int(adc_for(4.0))))
