from gen import *
np.set_printoptions(linewidth=200,precision=5,suppress=True)
wo=mkwo(400,1,60,200)
def diff(a,b):
    out=[]
    for f in ARGS.names:
        x,y=a[f].reshape(-1),b[f].reshape(-1)
        if ARGS[f].base.kind=='V': 
            if a[f].tobytes()!=b[f].tobytes(): out.append(f)
            continue
        ne=~((x==y)|((x!=x)&(y!=y)))
        if ne.any(): i=np.nonzero(ne)[0]; out.append((f,len(i),[(int(j),float(x[j]),float(y[j])) for j in i[:3]]))
    return out
def spikew(w): w=w.copy(); w[0]=int(w.max()*1.6); return w
for s0 in (1,2,5,6,7,8): 
    r,o,g,a=run(dev(),spikew(wo[0])[None],34.,seq=[s0]); print('solo seq',s0,o['data_type'],end=' ')
for k in range(3,9):
    r,o,g,a=run(dev(),np.vstack([wo[:k],spikew(wo[k])]),34.,seq=np.arange(100,101+k)); print('from100 count',k+1,o['data_type'][-1],end=' ')
for k in range(3,9):
    sq=np.arange(1,k+2); sq[-1]+=10
    r,o,g,a=run(dev(),np.vstack([wo[:k],spikew(wo[k])]),34.,seq=sq); print('gap count',k+1,o['data_type'][-1],end=' ')
print()
r,o,g,a=run(dev(),wo[:40],34.); b=a.copy(); r,o,g,b=run(dev(),spikew(wo[40])[None],35.,seq=[41],args=b)
for d_ in diff(a,b): print(d_)
print('out nonzero',[(f,o[f][0]) for f in OUT.names if np.any(o[f][0]!=0)]); print('dbg nonzero',[(f,g[f][0]) for f in DBG.names if np.any(g[f][0]!=0)])
print('--- err64'); b=a.copy(); r,o,g,b=run(dev(slope100=1.),wo[40:41],35.,seq=[41],args=b); print(diff(a,b)); print('out nonzero',[(f,o[f][0]) for f in OUT.names if np.any(o[f][0]!=0)]); print('dbg nonzero',[(f,g[f][0]) for f in DBG.names if np.any(g[f][0]!=0)],r)
