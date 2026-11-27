from sc import *
import json, make_fixtures as mf
sv=json.load(open('err_survey.json'))
runs=[x for x in sv if x['16']]
f=[n for n in co.DEBUG.names if n.startswith('err16') and co.DEBUG[n].shape==()]
for x in runs:
    seq,t,adc,T,c,o,g,s,d=runspec(x['spec'])
    i=x['16'][0]
    print('==',x['name'],'fire',i,'seq',seq[i],'condi',g['err16_condi'][i].tolist())
    ch=[(n,g[n][i-1],g[n][i]) for n in f if not (g[n][i-1]==g[n][i] or (np.isnan(g[n][i-1]) and np.isnan(g[n][i])))]
    for n,a,b in ch: print('   %-38s %.6g -> %.6g'%(n,a,b))
