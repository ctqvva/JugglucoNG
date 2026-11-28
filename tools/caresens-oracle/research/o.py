import ctypes,struct
import os,sys
sys.path.insert(0,os.path.join(os.path.dirname(os.path.abspath(__file__)),'..'))
from caresens_oracle import Reference

reference = Reference()
L, H = reference.lib, reference.handle
L.oracle_step.argtypes=[ctypes.c_void_p]*7; L.oracle_base.argtypes=[ctypes.c_void_p]; L.oracle_base.restype=ctypes.c_void_p
SZ=dict(dev=446,inp=74,cal=751,args=117312,out=155,dbg=1579)
class Vend:
    def reset(s,dev): s.dev=ctypes.create_string_buffer(dev,446); s.a=ctypes.create_string_buffer(117312)
    def step(s,seq,t,wo,temp,cal=None):
        i=ctypes.create_string_buffer(struct.pack('<HI30Hd',seq,t&0xffffffff,*wo,temp),74); c=ctypes.create_string_buffer(cal or b'',751);o=ctypes.create_string_buffer(4*155);d=ctypes.create_string_buffer(4*1579)
        r=L.oracle_step(H,s.dev,i,c,s.a,o,d)&255
        return r,o.raw[:155],d.raw[:1579]
    def args(s): return s.a.raw
