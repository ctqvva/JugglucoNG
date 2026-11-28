from sc import *
import json, collections
sv=json.load(open('err_survey.json')); cs=json.load(open('c0specs.json'))
cnt=collections.Counter(); ex=[]
for sp in cs+[x['spec'] for x in sv[:150]]:
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True)
    D=g['err4_delay_condi'].astype(bool); acc=(o['data_type']!=2)&((o['errcode']&64)==0); RW=g['tran_inA'].max(1)
    prev=np.full(len(seq),-np.inf)
    for i in range(1,len(seq)):
        j=i-1
        while j>=0 and not acc[j]: j-=1
        if j>=0 and seq[j]==seq[i]-1: prev[i]=RW[j]
    p=(seq<2017)&(np.maximum(RW,prev)<1.1)
    for i in np.nonzero((p!=D[:,0])&acc)[0]:
        cnt[(sp.get('start',1),'vendor' if D[i,0] else 'pred','seq>=2017' if seq[i]>=2017 else 'seq<2017')]+=1
        if len(ex)<8 and seq[i]<2017: ex.append((sp.get('start',1),int(seq[i]),int(s['idx'][i]),bool(D[i,0]),'raw %.3f prev %.3f'%(RW[i],prev[i]),'D',D[i].astype(int).tolist(),'err',int(o['errcode'][i])))
for k,v in sorted(cnt.items()): print(k,v)
for e in ex: print(e)
