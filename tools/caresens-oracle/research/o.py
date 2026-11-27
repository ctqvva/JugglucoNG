import ctypes,struct
import os
# build: cc -O2 -shared -o liboracle.dylib oracle.c (in this directory)
L=ctypes.CDLL(os.path.join(os.path.dirname(os.path.abspath(__file__)),'liboracle.dylib'))
L.oracle_open.restype=ctypes.c_void_p; L.oracle_error.argtypes=[ctypes.c_void_p]; L.oracle_error.restype=ctypes.c_char_p
L.oracle_step.argtypes=[ctypes.c_void_p]*7; L.oracle_base.argtypes=[ctypes.c_void_p]; L.oracle_base.restype=ctypes.c_void_p
SZ=dict(dev=446,inp=74,cal=751,args=117312,out=155,dbg=1579)
H=L.oracle_open(os.environ['CARESENS_REFERENCE_LIB'].encode())
assert not L.oracle_error(H),L.oracle_error(H)
class Vend:
    def reset(s,dev): s.dev=ctypes.create_string_buffer(dev,446); s.a=ctypes.create_string_buffer(117312)
    def step(s,seq,t,wo,temp,cal=None):
        i=ctypes.create_string_buffer(struct.pack('<HI30Hd',seq,t&0xffffffff,*wo,temp),74); c=ctypes.create_string_buffer(cal or b'',751);o=ctypes.create_string_buffer(155);d=ctypes.create_string_buffer(1579)
        r=L.oracle_step(H,s.dev,i,c,s.a,o,d)&255
        return r,o.raw,d.raw
    def args(s): return s.a.raw
