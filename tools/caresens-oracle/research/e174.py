from gen import *
N=2300; w=mkwo(N,1,80,200)
def lvl(sq):
    r,o,g,a,s=run(dev(),w[:len(sq)],34.,seq=np.array(sq),snap=True); return int(a['diabetes_level_diabetes'][0]), [int(x) for x in s['diabetes_level_diabetes'][[i for i in range(len(sq)) if sq[i] in (2016,2017,2018,2019,2020,2030)]]]
base=list(range(1,2101))
print('normal',lvl(base))
print('skip 2017',lvl([x for x in base if x!=2017]))
print('skip 2016-2017',lvl([x for x in base if x not in (2016,2017)]))
print('skip 2017-2019',lvl([x for x in base if x not in (2017,2018,2019)]))
print('start 1700 (few samples)',lvl(list(range(1700,2100))))
print('start 1100',lvl(list(range(1100,2100))))
print('start 1800',lvl(list(range(1800,2100))))
print('====')
for st in (1726,1727,1728,1729,1730):
    sq=list(range(st,2100)); r,o,g,a,s=run(dev(),w[:len(sq)],34.,seq=np.array(sq),snap=True); print(st,'count',int(a['diabetes_cnt_idx'][0]),'level',int(a['diabetes_level_diabetes'][0]))
