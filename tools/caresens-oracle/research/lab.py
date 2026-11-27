import sys,ctypes,numpy as np,random,math
import os; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0,os.path.join(os.path.dirname(os.path.abspath(__file__)),'..'))
from o import L,H
import layout
from layout import DEV,OUT,DBG,INP,CAL
ARGS=layout.struct('air1_opcal4_arguments_t',False)
L.oracle_run.argtypes=[ctypes.c_void_p]*9+[ctypes.c_int]
p=lambda a:a.ctypes.data_as(ctypes.c_void_p)
BASE=1709726400
import subprocess
import caresens_oracle as _co  # was a scratch build of the default device block
def dev(**k):
    d=_co.default_device()
    for a,b in k.items(): d[a][0]=b
    return d
def state(): return np.zeros(1,ARGS)
def run(d,wo,temp,seq=None,t=None,args=None,cal=None,snap=False):
    n=len(wo); i=np.zeros(n,INP)
    i['seq_number']=np.arange(1,n+1) if seq is None else seq
    i['measurement_time_standard']=(BASE+300*i['seq_number'].astype('u8')).astype('u4') if t is None else t
    i['workout']=wo; i['temperature']=temp
    a=state() if args is None else args
    c=np.zeros(1,CAL) if cal is None else cal
    o=np.zeros(n,OUT);g=np.zeros(n,DBG);r=np.zeros(n,'u1'); s=np.zeros(n,ARGS) if snap else None
    L.oracle_run(H,p(d),p(i),p(c),p(a),p(o),p(g),p(r),p(s) if snap else None,n)
    return (r,o,g,a,s) if snap else (r,o,g,a)
