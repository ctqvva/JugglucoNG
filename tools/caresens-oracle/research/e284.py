from sc import *
import json
sv=json.load(open('err_survey.json')); cs=json.load(open('c0specs.json'))
res={}; ex=[]
for sp in cs+[x['spec'] for x in sv[:150]]:
    seq,t,adc,T,c,o,g,s,d=runspec(sp,True)
    D=g['err4_delay_condi'].astype(bool); acc=(o['data_type']!=2)&((o['errcode']&64)==0); RW=g['tran_inA'].max(1)
    idx=s['idx'].astype(int)
    prev=np.full(len(seq),-np.inf)
    for i in range(1,len(seq)):
        j=i-1
        while j>=0 and not acc[j]: j-=1
        if j>=0 and seq[j]==seq[i]-1: prev[i]=RW[j]
    low=np.maximum(RW,prev)<1.1
    for k,ph in {'seq<2017':seq<2017,'idx<2017':idx<2017,'idx<2018':idx<2018,'idx<=2016':idx<=2016,'seq<2017|idx<2017':(seq<2017)|(idx<2017),'all':np.ones(len(seq),bool)}.items():
        p=ph&low; b=(p!=D[:,0])&acc; res[k]=res.get(k,0)+int(b.sum())
        if k=='idx<2017' and b.any() and len(ex)<6:
            i=np.nonzero(b)[0][0]; ex.append((sp.get('start',1),int(seq[i]),int(idx[i]),bool(D[i,0]),'raw %.3f prev %.3f'%(RW[i],prev[i])))
print(res); print(ex)
