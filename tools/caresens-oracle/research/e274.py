from sc import *
import random
R=random.Random(4); specs=[]
for it in range(80):
    spec=dict(seed=R.randrange(1,10**9),readings=1500,start=R.choice([1,30,600,1500]),slope100=R.uniform(2.6,8),eapp=R.choice([0.05,0.1]),
              noise=R.choice([12,25,50,100]),dropout_permille=10,dropout_min=1,dropout_max=R.choice([6,20]),lo=60,hi=250,knot_min=10,knot_max=60)
    specs.append(spec)
import json; json.dump(specs,open('c0specs.json','w'))
for ri,a,b in ((9,53,64),(0,262,270),(1,196,203)):
    seq,t,adc,T,c,o,g,s,d=runspec(specs[ri])
    print('run',ri,'slope %.2f eapp %.3f'%(specs[ri]['slope100'],specs[ri]['eapp']))
    for i in range(a,b): print('  ',i,'seq',seq[i],'C',g['err4_condi'][i].tolist(),'min %.3f range %.3f mdiff %.3f'%(g['err4_min'][i],g['err4_range'][i],g['err4_min_diff'][i]),'rmax %.3f mean %.3f'%(g['tran_inA'][i].max(),g['tran_inA'][i].mean()),'R %.1f init %.1f'%(o['result_glucose'][i],g['init_cg'][i]))
