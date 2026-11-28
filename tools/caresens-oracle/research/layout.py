import re,numpy as np
import os
H=open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','..','..','Common','src','main','cpp','air','air.hpp')).read()
T={'uint8_t':'u1','int8_t':'i1','uint16_t':'<u2','int16_t':'<i2','uint32_t':'<u4','float':'<f4','double':'<f8','char':'u1'}
def struct(name,pack):
    m=re.search(r'struct '+name+r'\s*\{(.*?)\}',H,re.S).group(1)
    f=[]
    for l in re.findall(r'([^;]+);',re.sub(r'//.*','',m)):
        g=re.match(r'\s*(\w+)\s+(\w+)\s*((?:\[\d+\])*)',l); dims=tuple(int(x) for x in re.findall(r'\[(\d+)\]',g[3]))
        t=T.get(g[1]) or struct(g[1],pack)
        f.append((g[2],t,dims) if dims else (g[2],t))
    return np.dtype(f,align=not pack)
DEV=struct('air1_opcal4_device_info_t',True);OUT=struct('air1_opcal4_output_t',True);DBG=struct('air1_opcal4_debug_t',True)
ARGS=struct('air1_opcal4_arguments_t',True);INP=struct('air1_opcal4_cgm_input_t',True);CAL=struct('air1_opcal4_cal_list_t',True)
if __name__=='__main__':
    print({k:globals()[k].itemsize for k in('DEV','OUT','DBG','ARGS','INP','CAL')}, struct('air1_opcal4_arguments_t',False).itemsize)
