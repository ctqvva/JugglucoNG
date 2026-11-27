# Survey: which error bits fire, where, in fixture and random scenarios.
from sc import *
import differential as df, random, json, make_fixtures as mf
out=[]
def survey(name,spec):
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    e=o['errcode']; row={'name':name,'spec':spec,'n':len(seq)}
    for b in (1,2,4,8,16,128):
        hit=np.nonzero(e&b)[0]
        row[str(b)]=[int(hit[0]),int(len(hit)),int(hit[-1])] if len(hit) else None
    out.append(row); return row
for name,spec in list(mf.DEVELOPMENT.items())+list(mf.HOLDOUT.items()) if isinstance(mf.DEVELOPMENT,dict) else []:
    survey(name,spec)
r=random.Random(5)
for i in range(250): survey('rand%d'%i, df.random_spec(r))
json.dump(out,open('err_survey.json','w'))
for b in ('1','2','4','8','16','128'):
    rs=[x for x in out if x[b]]
    print('bit',b,'runs',len(rs),'e.g.',[(x['name'],x[b]) for x in rs[:6]])
