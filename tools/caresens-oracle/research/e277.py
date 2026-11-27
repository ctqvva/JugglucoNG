import sys,ast
from sc import *
spec=ast.literal_eval(sys.argv[1])
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
i=int(np.nonzero(seq>=2016)[0][0]); i=i if seq[i]==2016 else i-1
print('seq',seq[i])
nm=[n for n in co.STATE.names if 'mdc' in n or 'diabetes_cnt' in n or 'init_value' in n]
print({n:s[n][i] for n in nm})
w=(seq>=865)&(seq<=2016)
acc=o['data_type']!=2
for lab,m in (('accepted',acc),('acc & type!=4',acc&(o['data_type']!=4)),('acc & no err',acc&(o['errcode']==0)),('acc & no err4',acc&((o['errcode']&4)==0))):
    print(lab,int((w&m).sum()),'sum corr %.6f'%g['corrected_re_current'][w&m].sum() if 'corrected_re_current' in g.dtype.names else '')
R=o['result_glucose']; X=g['corrected_re_current']; typ=o['data_type']
ai=np.nonzero(acc)[0]
prevR=np.full(len(seq),np.nan); prevT=np.full(len(seq),-1)
for a,b in zip(ai[:-1],ai[1:]): prevR[b]=R[a]; prevT[b]=typ[a]
cands={'R in [40,600]':(R>=40)&(R<=600),'prevR in [40,600]':(prevR>=40)&(prevR<=600),'type!=4 or prev':(typ!=4),
       'prev type!=4':prevT!=4,'type!=4 & prev type!=4':(typ!=4)&(prevT!=4),'type!=4 | prevtype!=4':(typ!=4)|(prevT!=4)}
for k,m in cands.items():
    mm=w&acc&m; print('%-26s n %d sum %.6f'%(k,mm.sum(),X[mm].sum()))
print('vendor',s['sum_curr_cnt_for_mdc'][i],s['sum_curr_for_mdc'][i])
