from sc import *
import json
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json'))
fl=['err1_random_noise_temp_break','err4_delay_flag','err2_delay_flag','err1_TD_temporary_break_flag','err1_result_condition_TD','err1_is_contact_bad','err1_result','err1_result_TD']
print({n:co.DEBUG[n].shape for n in fl})
rows=[]
def add(o,g):
    m=(o["data_type"]!=2)&((o["errcode"]&64)==0)
    if not m.any(): return
    cols=[(o['data_type'][m]==4)]+[(g[n][m].reshape(m.sum(),-1)!=0).any(1) for n in fl]
    rows.append(np.stack(cols,1))
for x in sv[:120]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); add(o,g)
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); add(o,g)
r=np.concatenate(rows); y=r[:,0]
import itertools; res=[]
for k in range(1,4):
    for comb in itertools.combinations(range(1,len(fl)+1),k):
        p=r[:,list(comb)].any(1)
        mm=(p!=y).sum()
        res.append((mm,[fl[c-1] for c in comb],(p&~y).sum(),(~p&y).sum()))
        if False: print([fl[c-1] for c in comb],'mism',mm,'fp',(p&~y).sum(),'fn',(~p&y).sum())
for x in sorted(res,key=lambda z:z[0])[:8]: print(x)
print('type4 count',y.sum(),'rows',len(y))
