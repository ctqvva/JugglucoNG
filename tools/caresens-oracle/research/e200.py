from sc import *
import make_fixtures as mf
np.set_printoptions(linewidth=200,precision=5,suppress=True)
spec=mf.DEVELOPMENT['life-lot2-sensitivity-loss']
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
f=[n for n in co.DEBUG.names if n.startswith('err16')]
print('state err16:',[ (n,co.STATE[n].shape) for n in co.STATE.names if 'err16' in n])
i0=2637
for n in f:
    v=g[n]; 
    nz=np.nonzero(np.nan_to_num(v.reshape(len(v),-1),nan=0).any(1))[0]
    print('%-40s first nonzero %s  shape %s  at fire %s  before %s'%(n, nz[0] if len(nz) else None, v.shape[1:], v[i0], v[i0-1]))
print('seq at fire',seq[i0],'condi',g['err16_condi'][i0-3:i0+3])
