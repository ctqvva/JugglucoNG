# full-resolution transitions of err16 conditions
from sc import *
import pickle
from err16lib import decay_specs
f=[n for n in co.DEBUG.names if n.startswith('err16') and co.DEBUG[n].shape==()]
TR=[]
for it, spec in enumerate(decay_specs()):
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    C=g['err16_condi'].astype(int)
    for i in np.nonzero(np.diff(C,axis=0).any(1))[0]+1:
        TR.append(dict(run=it,i=int(i),seq=int(seq[i]),before=C[i-1].tolist(),after=C[i].tolist(),
            a={n:float(g[n][i-1]) for n in f},b={n:float(g[n][i]) for n in f}))
pickle.dump(TR,open('err16_tr.pkl','wb')); print(len(TR))
