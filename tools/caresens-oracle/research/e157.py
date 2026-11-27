from gen import *
np.set_printoptions(linewidth=200,precision=4,suppress=True)
T=np.array([30.,31,32,33,34,35,36,37,38,39,40,41])
for nm,mod in (('dup',lambda sq:np.r_[sq[:6],sq[5:11]]),('back2',lambda sq:np.r_[sq[:6],sq[3:9]]),('back1',lambda sq:np.r_[sq[:6],sq[4:10]])):
    sq=mod(np.arange(1,13))
    r,o,g,a,s=run(dev(),mkwo(12,1),T,seq=sq,snap=True)
    print(nm,[ (int(sq[i]),round(float(g['temp_local_mean'][i]),3),s['slope_ratio_temp_buffer'][i].round(1).tolist()) for i in range(4,9)])
