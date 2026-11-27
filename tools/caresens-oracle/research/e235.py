from craft import *
for c in (0.2,0.35,0.45):
    o,g,s=run_plateau(c,12)
    print('=== c',c)
    for i in range(498,516):
        print(i,'5min %.4f'%g['tran_inA_5min'][i],'min %.4f range %.4f mdiff %.4f'%(g['err4_min'][i],g['err4_range'][i],g['err4_min_diff'][i]),'C',g['err4_condi'][i],'D',g['err4_delay_condi'][i],'flag',g['err4_delay_flag'][i],'err',o['errcode'][i]&4,'type',o['data_type'][i])
