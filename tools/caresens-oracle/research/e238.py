from craft import *
# level grid with long plateau: which C/D bits are on at plateau steady state (record 508)
for c in np.round(np.arange(-0.3,1.6,0.05),3):
    o,g,s=run_plateau(float(c),12)
    print('c %5.2f 5min %.4f'%(c,g['tran_inA_5min'][508]),'C',g['err4_condi'][508],'D',g['err4_delay_condi'][508],'D@500',g['err4_delay_condi'][500],'type',o['data_type'][508],'err4',bool(o['errcode'][515]&4), 'glu %.1f'%o['result_glucose'][508])
