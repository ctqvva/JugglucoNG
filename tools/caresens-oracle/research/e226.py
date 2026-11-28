# dataset of err16 debug scalars + condi, records with seq>=600, decaying-sensitivity runs
from sc import *
from err16lib import decay_specs
f=[n for n in co.DEBUG.names if n.startswith('err16') and co.DEBUG[n].shape==()]
X=[];Y=[];META=[]
for it, spec in enumerate(decay_specs()):
    seq,t,adc,T,c,o,g,s,d=runspec(spec)
    m=np.nonzero(seq>=600)[0][::3]
    X.append(np.stack([g[n][m] for n in f],1)); Y.append(g['err16_condi'][m]); META.append(np.stack([np.full(len(m),it),seq[m]],1))
np.savez('err16_ds.npz',X=np.concatenate(X),Y=np.concatenate(Y),M=np.concatenate(META),names=np.array(f))
Y=np.concatenate(Y); print(Y.shape, Y.sum(0))
