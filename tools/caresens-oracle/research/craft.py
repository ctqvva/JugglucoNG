from stress import *
DEV=co.default_device()
V=float(DEV['vref'][0]); E=float(DEV['eapp'][0])
def adc_for(cur): return int(round((cur/100+E)*40950/V))
def cur_of(a): return (a*V/40950-E)*100
def run_plateau(c,K,a=500,n=700,shape=None):
    seq,t,adc,T=base(); seq,t,adc,T=seq[:n],t[:n],adc[:n].copy(),T[:n]
    adc[a:a+K]=adc_for(c) if shape is None else shape
    cc,o,g,_,s=REF.run(DEV,co.records(seq,t,adc.astype("u2"),T),snapshots=True)
    return o[:,0],g[:,0],s
