from craft import *
np.set_printoptions(linewidth=250,precision=4,suppress=True)
o,g,s=run_plateau(0.35,12)
i=503
A=s['err4_inA'][i]; print('err4_inA tail 35',A[-35:])
print('tran_inA 503',g['tran_inA'][i][:6], '1min',g['tran_inA_1min'][i])
print('min_prev tail',s['err4_min_prev'][i][-6:],'range_prev tail',s['err4_range_prev'][i][-6:],'mdiff_prev tail',s['err4_min_diff_prev'][i][-6:],'delay arr tail',s['err4_delay_flag_arr'][i][-8:])
# find tran_inA history in err4_inA
for k in range(12):
    r=g['tran_inA_1min'][i-k]
    print(k, 'match 1min of rec', i-k, [int(np.argmin(np.abs(A-v))) for v in r], [float(np.min(np.abs(A-v))) for v in r][:2])
