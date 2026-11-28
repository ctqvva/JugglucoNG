from sc import *
import random, pickle
R=random.Random(4); runs=[]
for it in range(80):
    spec=dict(seed=R.randrange(1,10**9),readings=1500,start=R.choice([1,30,600,1500]),slope100=R.uniform(2.6,8),eapp=R.choice([0.05,0.1]),
              noise=R.choice([12,25,50,100]),dropout_permille=10,dropout_min=1,dropout_max=R.choice([6,20]),lo=60,hi=250,knot_min=10,knot_max=60)
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    runs.append(dict(seq=seq,typ=o['data_type'].copy(),C=g['err4_condi'].copy(),D=g['err4_delay_condi'].copy(),rmax=g['tran_inA'].max(1),rmean=g['tran_inA'].mean(1),m5=g['tran_inA_5min'].copy(),R=o['result_glucose'].copy(),init=g['init_cg'].copy(),sl=float(d['slope100'][0]),err=o['errcode'].copy()))
pickle.dump(runs,open('c0runs.pkl','wb'))
