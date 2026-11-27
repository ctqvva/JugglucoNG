from gen import *
np.set_printoptions(linewidth=220,precision=3,suppress=True)
rng=np.random.default_rng(3); N=700; sq=np.cumsum(np.where(rng.random(N)<.05,rng.integers(2,9,N),1)); 
w=mkwo(N,1); r,o,g,a,s=run(dev(),w,34.,seq=sq,snap=True)
print('stage vals',np.unique(o['current_stage'],return_counts=True),'first stage1 at',sq[np.argmax(o['current_stage']>0)], 'cal avail',np.unique(o['cal_available_flag']), np.unique(g['cal_available_flag']))
bad=0
for i in range(N):
    S=s['smooth_sig_in'][i]; T=s['smooth_time_in'][i]; Q=o['smooth_seq'][i]; F=o['smooth_fixed_flag'][i]; R=o['smooth_result_glucose'][i]
    if i<14 or (i>100 and i<106) or (sq[i]-sq[i-1]>1 and bad<6):
        bad+=sq[i]-sq[i-1]>1
        print(sq[i],'Q',Q,'F',F,'R',R.round(1),'S38',S[3:9].round(1),'T',(T[3:9].astype(np.int64)-BASE)//300 if T[3]>0 else T[3:9],'frep',s['smooth_f_rep_in'][i],'stage',o['current_stage'][i])
