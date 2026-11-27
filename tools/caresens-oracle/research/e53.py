from gen import *
np.set_printoptions(linewidth=220,precision=8,suppress=True)
def srt(t,sq=1,**k):
    r,o,g,a=run(dev(**k),np.full((1,30),9000,'u2'),float(t),seq=[sq]); return float(g['slope_ratio_temp'][0]),float(g['temp_local_mean'][0]),int(o['errcode'][0])
xs=np.arange(-10,60,.25); ys=np.array([srt(x)[0] for x in xs])
d2=np.diff(ys,2); brk=[float(xs[i+1]) for i in range(len(d2)) if abs(d2[i])>1e-9]; print('kinks',brk)
for lo,hi in((-10,10),(20,27),(28,33),(34.5,37.5),(38.5,45),(50,59)):
    m=(xs>=lo)&(xs<=hi); print(lo,hi,np.polyfit(xs[m],ys[m],1),np.abs(np.polyval(np.polyfit(xs[m],ys[m],1),xs[m])-ys[m]).max(),ys[m][0])
for s in (1,100,1000,3000): print(s,[srt(t,s)[0] for t in (30.,34.,36.,39.)])
print('lot2',[srt(t,eapp=.05)[0] for t in (30.,34.,36.,39.)], srt(36.,slope100=6.))
# buffer averaging
T=np.array([34,36,33,35,37,32,34.5,30,31,40,25.,36]); r,o,g,a,s=run(dev(),np.full((12,30),9000,'u2'),T,snap=True)
for i in range(12): print(T[i],g['temp_local_mean'][i],s['slope_ratio_temp_buffer'][i],g['slope_ratio_temp'][i],srt(g['temp_local_mean'][i])[0], s['temp_prev'][i])
