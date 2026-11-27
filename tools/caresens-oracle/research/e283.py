from sc import *
import json
sv=json.load(open('err_survey.json')); cs=json.load(open('c0specs.json'))
for sp in [x for x in cs if x['start'] in (600,1500)][:8]+[x['spec'] for x in sv[:200] if x['spec'].get('start',1) in (600,1500) and x['spec']['readings']>=2500][:6]:
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True)
    D=g['err4_delay_condi'].astype(bool)
    d0=np.nonzero(D[:,0])[0]; d4=np.nonzero(D[:,4])[0]; d1=np.nonzero(D[:,1])[0]
    print('start',sp['start'],'n',len(seq),'last seq',seq[-1],'D0 seq range',(seq[d0].min(),seq[d0].max()) if len(d0) else None,'D1 first seq',seq[d1].min() if len(d1) else None,'D4 seq range',(seq[d4].min(),seq[d4].max()) if len(d4) else None)
