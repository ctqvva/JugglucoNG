import craft
from craft import *
def sig(c,dev):
    craft.DEV=dev
    o,g,s=run_plateau(c,12)
    return (g['err4_condi'][495:520].tobytes(),g['err4_delay_condi'][495:520].tobytes(),(o['errcode']&4).tobytes())
base_dev=co.default_device()
ref={c:sig(c,base_dev) for c in (0.35,0.45)}
params=[n for n in base_dev.dtype.names if n.startswith('err345') or n.startswith('err1_') or n.startswith('err2_') or n.startswith('err6')]
for n in params:
    shape=base_dev[n][0].shape; idxs=list(np.ndindex(shape)) if shape else [()]
    for ix in idxs:
        for f in (0.5,2.0):
            d=co.default_device(); v=d[n][0][ix] if shape else d[n][0]
            nv=v*f if v!=0 else (1 if f>1 else -1)
            if shape: d[n][0][ix]=nv
            else: d[n][0]=nv
            ch=[c for c in (0.35,0.45) if sig(c,d)!=ref[c]]
            if ch: print('param',n,ix,v,'->',nv,'changes err4 behaviour for',ch)
craft.DEV=base_dev
