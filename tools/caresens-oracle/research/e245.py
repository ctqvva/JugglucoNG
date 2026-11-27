from sc import *
import json, differential as df, random
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json'))
tot=bad4=badC=0; ex=[]
def chk(o,g,sl,name):
    global tot,bad4,badC
    typ=o['data_type']; R=o['result_glucose']
    acc=typ!=2
    p4=(np.round(R)<20)&acc
    b=np.nonzero(p4!=(typ==4))[0]; bad4+=len(b)
    if len(b) and len(ex)<6: ex.append((name,b[:3].tolist(),[(float(R[i]),int(typ[i]),int(o['errcode'][i])) for i in b[:3]]))
    raw=g['tran_inA']; C=g['err4_condi'][:,0].astype(bool); pred=np.zeros(len(C),bool)
    for i in range(3,len(C)):
        m=raw[i-3:i+1].max(); pred[i]=m<0.5 and m*100/sl<10
    badC+=int((pred!=C).sum()); tot+=len(C)
for x in sv[:120]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(o,g,float(d['slope100'][0]),x['name'])
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(o,g,3.5226,nm)
print('records',tot,'type4 mismatches',bad4,'C0 mismatches',badC); print(ex)
