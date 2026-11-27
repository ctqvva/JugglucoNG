from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=4,suppress=True)
for nm,sp in list(mf.DEVELOPMENT.items())+list(mf.HOLDOUT.items()):
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True)
    k=kt(d,adc.astype('u2'),T,seq,t)
    ok=(o['data_type']!=2)&((o['errcode']&64)==0)
    gd=np.abs(k['g']-o['result_glucose'])*ok; cgd=np.abs(k['cg']-g['init_cg'])*ok; lv=(k['lvl']!=s['diabetes_level_diabetes'])
    print('%-30s glu %.1e first %s  cg first %s  level first %s'%(nm,gd.max(),np.argmax(gd>1e-7)+1 if (gd>1e-7).any() else '-',np.argmax(cgd>1e-7)+1 if (cgd>1e-7).any() else '-',np.argmax(lv)+1 if lv.any() else '-'))
