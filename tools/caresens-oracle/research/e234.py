from craft import *
for c in (0.0,0.2,0.29,0.31,0.4,0.49,0.51,0.6):
    for K in (1,2,3,4,8):
        o,g,s=run_plateau(c,K)
        C=g['err4_condi'][495:500+K+6]; D=g['err4_delay_condi'][495:500+K+6]
        on=[ (i, C[i-495].tolist()) for i in range(495,500+K+6) if C[i-495].any()]
        print('c',c,'K',K,'err4 first',(np.nonzero(o['errcode']&4)[0][:1]).tolist(),'condi on',on[:3],'delay',[(i,D[i-495].tolist()) for i in range(495,500+K+6) if D[i-495].any()][:2])
