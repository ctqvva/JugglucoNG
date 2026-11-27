from gen import *
N=700; rng=np.random.default_rng(3); T=34+1.5*np.sin(np.arange(N)/37)+rng.normal(0,.1,N)
wo=mkwo(N,1,T=T); b=run(dev(),wo,T)
print('glu',b[1]['result_glucose'][[30,100,300,600]],'err',np.unique(b[1]['errcode'],return_counts=True))
order=[n for n in DBG.names if n not in('seq_number_original','seq_number_final')]
for f in DEV.names:
    shp=DEV[f].shape; 
    for idx in np.ndindex(*shp) if shp else [()]:
        eff=set()
        for how in (1,2,3):
            d=dev(); v=d[f][0][idx] if shp else d[f][0]
            nv=(float(v)*[1.07,.5,3][how-1]+[0.003,-.2,.5][how-1]) if DEV[f].base.kind=='f' else [int(v)+1,max(int(v)//2,0),min(int(v)*2+3,127)][how-1]
            if shp: d[f][0][idx]=nv
            else: d[f][0]=nv
            r,o,g,a=run(d,wo,T)
            for n in order:
                if not np.array_equal(g[n],b[2][n],equal_nan=True): eff.add(n)
            if not np.array_equal(o['result_glucose'],b[1]['result_glucose']): eff.add('RESULT')
            if not np.array_equal(o['errcode'],b[1]['errcode']): eff.add('ERRCODE')
            if not np.array_equal(r,b[0]): eff.add('RET')
        if eff:
            l=[n for n in order if n in eff]; print(f,idx,l[:4],len(l),[x for x in('RESULT','ERRCODE','RET') if x in eff])
