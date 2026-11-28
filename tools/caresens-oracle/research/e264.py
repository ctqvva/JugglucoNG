from sc import *
import json
from stress import run as srun, NAMES
sv=json.load(open('err_survey.json'))
def chk(seq,o,g,sl,name):
    raw=g['tran_inA']; C=g['err4_condi'][:,0].astype(bool); pred=np.zeros(len(C),bool)
    for i in range(3,len(C)):
        m=raw[i-3:i+1].max(); pred[i]=m<0.5 and m*100/sl<10
    b=np.nonzero(pred!=C)[0]
    if len(b): print(name,len(b),[(int(i),int(seq[i]),bool(C[i]),int(o['data_type'][i]),int(o['errcode'][i])) for i in b[:4]], 'types around',o['data_type'][b[0]-4:b[0]+1].tolist(), 'raw max', np.round(raw[b[0]-4:b[0]+1].max(1),3).tolist())
for x in sv[:120]:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec']); chk(seq,o,g,float(d['slope100'][0]),x['name'])
for nm in NAMES:
    seq,t,x_,T,o,g=srun(nm); chk(seq,o,g,3.5226,nm)
