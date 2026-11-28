from sc import *
import json
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json'))
rows=[]
def add(o,g):
    m=(o['data_type']!=2)&((o['errcode']&64)==0)
    rows.append(np.stack([o['result_glucose'][m],g['init_cg'][m],g['out_rescale'][m],g['out_drift'][m],(o['data_type'][m]==4).astype(float),g['tran_inA_5min'][m]],1))
for x in sv[:120]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); add(o,g)
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); add(o,g)
r=np.concatenate(rows); y=r[:,4]==1
R,I,RS=r[:,0],r[:,1],r[:,2]
for nm,p in (('round(R)<20',np.round(R)<20),('R<20 & init<20',(np.round(R)<20)&(I<20)),('R<20&resc<20',(np.round(R)<20)&(RS<20)),('round(R)<20 & round(resc)<20',(np.round(R)<20)&(np.round(RS)<20)),('resc<20',RS<20)):
    print('%-34s mism %d  (fp %d fn %d)'%(nm,(p!=y).sum(),(p&~y).sum(),(~p&y).sum()))
m=(np.round(R)<20)&~y; print('fp examples R, init, resc', r[m][:6,:3])
m=(np.round(R)<20)&y; print('tp resc range',RS[m].min(),RS[m].max())
