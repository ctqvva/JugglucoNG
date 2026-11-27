from gen import *
np.set_printoptions(linewidth=220,precision=9,suppress=True)
N=4320; wo=mkwo(N,1); r,o,g,a=run(dev(),wo,34.)
inv=g['out_iir']/g['out_drift']; ra=g['out_drift']/g['out_iir']; sq=np.arange(1,N+1.)
end=np.argmax(inv>=1); print('ends at seq',end+1,inv[end-2:end+2])
for nm,y in(('inv',inv),('ratio',ra),('loginv',np.log(inv)),):
    for deg in(1,2,3,4):
        c=np.polyfit(sq[:end],y[:end],deg); print(nm,deg,np.abs(np.polyval(c,sq[:end])-y[:end]).max(),c)
# seq vs time dependence
t=BASE+300*np.arange(1,61); sqv=np.arange(1,61)+1000
r,o,g1,a=run(dev(),wo[:60],34.,seq=sqv,t=BASE+300*sqv); print('start at seq1001:',(g1['out_drift']/g1['out_iir'])[:3],'vs',ra[1000:1003])
r,o,g1,a=run(dev(),wo[:60],34.,seq=sqv,t=t); print('seq1001 time like 1:',(g1['out_drift']/g1['out_iir'])[:3])
r,o,g1,a=run(dev(sensor_start_time=BASE-86400*3),wo[:60],34.); print('start time 3d earlier',(g1['out_drift']/g1['out_iir'])[:3])
for f in ('out_rescale',):
    k=g['out_rescale']/g['init_cg']; print(k[[0,287,500,575,576,577,863,864,865,1000,1500,2000,2500,3000,3500,4319]])
    print('first !=1',np.argmax(np.abs(k-1)>1e-12)+1)
