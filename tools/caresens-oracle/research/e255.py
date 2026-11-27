from sc import *
import json
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json'))
tot=0; bad=np.zeros(5,int); ex={k:[] for k in range(5)}
def chk(seq,o,g,name):
    global tot
    R=np.round(o['result_glucose']); X=g['tran_inA_5min']; D=g['err4_delay_condi'].astype(bool)
    acc=(o['data_type']!=2)&((o['errcode']&64)==0)
    ph1=seq<2017; ph2=(seq>=865)&(seq<2017); ph3=seq>=2017
    P=np.stack([ph1&(X<1.1), ph2&(X<1.5), ph2&(R<40)&(X<3.0), R<20, ph3&(R<30)],1)&acc[:,None]
    for k in range(5):
        b=np.nonzero((P[:,k]!=D[:,k])&acc)[0]; bad[k]+=len(b)
        if len(b) and len(ex[k])<4: ex[k].append((name,int(b[0]),int(seq[b[0]]),bool(D[b[0],k]),float(X[b[0]]),float(o['result_glucose'][b[0]])))
    tot+=acc.sum()
for x in sv:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g,x['name'])
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g,nm)
print('records',tot,'mismatch per D',bad.tolist())
for k in range(5): print(k,ex[k])
