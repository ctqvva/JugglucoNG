from sc import *
np.set_printoptions(linewidth=230,precision=4,suppress=True)
seq,t,adc,T,c,o,g,s,d=runspec(mf.HOLDOUT['holdout-life-other-lot1'],True)
e=o['errcode']; nz=np.nonzero(e&16)[0]; print('err16 at',nz[:5]+1,nz[-3:]+1,len(nz))
k0=nz[0]
fl=[n for n in co.DEBUG.names if n.startswith('err16')]
for k in (k0-2,k0-1,k0,k0+1):
    print(k+1,'G %.1f'%o['result_glucose'][k],'condi',g['err16_condi'][k], {n.replace('err16_CGM_ISF_',''):round(float(g[n][k]),4) for n in fl if n!='err16_condi'})
print('level',s['diabetes_level_diabetes'][k0])
