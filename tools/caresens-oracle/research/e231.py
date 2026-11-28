from stress import *
np.set_printoptions(linewidth=220,precision=4,suppress=True)
seq,t,x,T,o,g=run('low60')
f=['err4_min','err4_range','err4_min_diff','err4_condi','err4_delay_condi','err4_delay_flag']
print({n:co.DEBUG[n].shape for n in f})
for i in list(range(495,512))+[600,699,700,701,710]:
    print(i,'res %.1f'%o['result_glucose'][i],'type',o['data_type'][i],'err',o['errcode'][i],' '.join('%s=%s'%(n[5:],g[n][i]) for n in f), '5min %.4f'%g['tran_inA_5min'][i])
