from gen import *
np.set_printoptions(linewidth=200,precision=6,suppress=True)
def q(**k):
    d=dev(**k); wo=np.full((1,30),10000,'u2'); r,o,g,a=run(d,wo,34.); return int(r[0]),int(o['errcode'][0]),int(o['data_type'][0]),round(float(g['tran_inA_5min'][0]),4),int(a['lot_type'][0]),round(float(g['ycept'][0]-g['tran_inA_5min'][0]),5),round(float(g['slope_ratio_temp'][0]),5),round(float(g['init_cg'][0]),3)
def sweep(f,xs):
    prev=None
    for x in xs:
        v=q(**{f:x}); k=(v[0],v[1],v[2],v[4],v[5],v[6])
        if k!=prev: print(f,x,v); prev=k
sweep('eapp',[x/10000 for x in range(-3000,5000)])
sweep('slope100',[x/100 for x in range(-100,2000)]); sweep('vref',[x/1000 for x in range(-500,6000)])
