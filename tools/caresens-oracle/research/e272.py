from sc import *
import random, pickle
R=random.Random(4); rows=[]
for it in range(80):
    spec=dict(seed=R.randrange(1,10**9),readings=1500,start=R.choice([1,30,600,1500]),slope100=R.uniform(2.6,8),eapp=R.choice([0.05,0.1]),
              noise=R.choice([12,25,50,100]),dropout_permille=10,dropout_min=1,dropout_max=R.choice([6,20]),lo=60,hi=250,knot_min=10,knot_max=60)
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True); cl=s["prev_outlier_removed_curr"][:,30:60]
    sl=float(d['slope100'][0]); raw=g['tran_inA']
    for i in range(4,len(seq)):
        if o['data_type'][i]==2: continue
        w=raw[i-3:i+1]
        if w.max()>1.5: continue
        rows.append(dict(cl=cl[i-3:i+1].copy(),C0=int(g['err4_condi'][i][0]),D=g['err4_delay_condi'][i].tolist(),sl=sl,w=w.copy(),m5=g['tran_inA_5min'][i-3:i+1].copy(),m1=g['tran_inA_1min'][i-3:i+1].copy(),
            R=float(o['result_glucose'][i]),init=float(g['init_cg'][i]),resc=float(g['out_rescale'][i]),seq=int(seq[i]),cons=bool(np.all(np.diff(seq[i-3:i+1])==1) and (o['data_type'][i-3:i+1]!=2).all())))
pickle.dump(rows,open('c0rows2.pkl','wb')); print(len(rows), sum(r['C0'] for r in rows))
