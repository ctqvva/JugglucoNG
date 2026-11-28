from stress import *
np.set_printoptions(linewidth=250,precision=3,suppress=True)
f=[n for n in co.DEBUG.names if n.startswith('err2')]
print({n:co.DEBUG[n].shape for n in f})
seq,t,x,T,o,g=run('high3')
for i in list(range(497,513)):
    print(i,'err',o['errcode'][i],'R %.1f 5min %.2f'%(o['result_glucose'][i],g['tran_inA_5min'][i]),' '.join('%s=%s'%(n[5:],np.round(g[n][i],3)) for n in f))
