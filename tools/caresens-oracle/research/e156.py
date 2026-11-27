from gen import *
np.set_printoptions(linewidth=200,precision=4,suppress=True)
T=np.array([30.,31,32,33,34,35,36,37,38,39,40,41])
for gap in (1,2,3,5):
    sq=np.arange(1,13); sq[6:]+=gap-1
    r,o,g,a,s=run(dev(),mkwo(12,1),T,seq=sq,snap=True)
    print('gap',gap,[ (int(sq[i]),round(float(g['temp_local_mean'][i]),3),s['slope_ratio_temp_buffer'][i].round(1).tolist()) for i in range(4,9)])
