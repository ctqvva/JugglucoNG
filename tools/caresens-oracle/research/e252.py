exec(open('e251.py').read().split("for sl,ea in")[0])
d=co.default_device()
for c in (-0.2,-0.5,-1.0,-2.0,-4.0):
    o,g=sim(adc_for(c),d)
    C=g['err4_condi'].astype(int)
    ch=np.nonzero((np.diff(C,axis=0)!=0).any(1))[0]+1
    print('c',c,[(int(i),C[i].tolist()) for i in ch[:8]], 'min %.3f'%g['err4_min'][1000],'mdiff',g['err4_min_diff'][299:304].round(3))
