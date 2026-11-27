import craft
from craft import *
exec(open('e240.py').read().split("for sl in")[0])
for sl,ea in ((3.5226,0.06),(7.0,0.06)):
    d=co.default_device(); d['slope100'][0]=sl; d['eapp'][0]=ea
    craft.E=float(d['eapp'][0])
    for nm,p in (('C0',lambda g,o:g['err4_condi'][508][0]==1),('D0',lambda g,o:g['err4_delay_condi'][508][0]==1),('D3',lambda g,o:g['err4_delay_condi'][508][3]==1)):
        print('slope',sl,'eapp',ea,nm,bisect(d,p,1500,4500))
