import subprocess,struct,numpy as np
from lab import *
import os
# build: kotlinc kotlin/Cli.kt <engine sources> -include-runtime -d eng.jar
JAR=os.environ.get('CARESENS_LEGACY_JAR',os.path.join(os.path.dirname(os.path.abspath(__file__)),'eng.jar'))
JAVA=os.path.join(os.environ['JAVA_HOME'],'bin','java')
RT=np.dtype([('sf','<i4'),('g','<f8'),('tr','<f8'),('err','<i4'),('dt','<i4'),('exact','<i4'),('h','<f8',6),('hs','<i4',6),('cg','<f8'),('res','<f8'),('corr','<f8'),('lvl','<i4'),('one','<f8',5)])
def kt(d,wo,T,seq,t,restore=-1,vstate=None):
    n=len(wo); i=np.zeros(n,INP); i['seq_number']=seq;i['measurement_time_standard']=t;i['workout']=wo;i['temperature']=T
    hdr=struct.pack('<fffiii',float(d['slope100'][0]),float(d['vref'][0]),float(d['eapp'][0]),n,restore,-1 if vstate is None else 0)+(b'' if vstate is None else vstate.tobytes())
    out=subprocess.run([JAVA,'-cp',JAR,'CliKt'],input=hdr+i.tobytes(),capture_output=True,check=True).stdout
    return np.frombuffer(out,RT)
def cmp(nm,wo,T=34.,d=None,seq=None,t=None,restore=-1,show=3):
    d=dev() if d is None else d; N=len(wo); sq=np.arange(1,N+1) if seq is None else np.asarray(seq); t=(BASE+300*sq.astype('u8')).astype('u4') if t is None else np.asarray(t); T=np.broadcast_to(np.asarray(T,float),(N,)).copy()
    r,o,g,a,s=run(d,wo,T,seq=sq,t=t,snap=True); k=kt(d,wo,T,sq,t,restore)
    ok=k['exact']==1
    use=ok&(o['data_type']!=1)
    def e(x,y): 
        z=np.abs(x-y); z=np.where(np.isnan(x)&np.isnan(y),0,z); return z
    gerr=e(k['g'],o['result_glucose'])[use&(o['errcode']==0)&(o['data_type']==0)]
    res=dict(glu=gerr.max() if len(gerr) else 0, tr=e(k['tr'],o['trendrate'])[use&(o['data_type']==0)].max(), hist=e(k['h'],o['smooth_result_glucose']).max(1)[use].max(),hseq=int((k['hs']!=o['smooth_seq']).any(1)[use].sum()),
             err=int((k['err']!=o['errcode'])[ok].sum()), dtype2=int(((k['dt']==2)!=(o['data_type']==2)).sum()), cg=e(k['cg'],g['init_cg'])[use&(o['data_type']==0)].max(),inexact=int((~ok).sum()), vendor_err_kinds=sorted(set(o['errcode'].tolist())))
    print(nm,{a_:(float('%.2g'%b_) if isinstance(b_,float) else b_) for a_,b_ in res.items()})
    return r,o,g,a,s,k
