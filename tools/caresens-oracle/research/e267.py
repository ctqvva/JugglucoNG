from craft import *
seq,t,adc,T=base(); n=1500
def go(label,mod):
    a=adc[:n].copy(); s2=seq[:n].copy(); t2=t[:n].copy(); mod(a,s2,t2)
    cc,o,g,_,s=REF.run(DEV,co.records(s2,t2,a.astype('u2'),T[:n]),snapshots=True); o=o[:,0]
    e=(o['errcode']&4)!=0; ch=np.nonzero(np.diff(e.astype(int)))[0]+1
    print(label,'err4 transitions',[(int(i),int(e[i])) for i in ch[:6]],'types at transitions',[int(o['data_type'][i]) for i in ch[:6]], 'result_prev', s['err4_result_prev'][-1] if 'err4_result_prev' in s.dtype.names else '')
def low(a,s2,t2): a[50:60]=adc_for(0.2)
go('low 50..59',low)
def lowrej(a,s2,t2): low(a,s2,t2); a[80,0]=a[80,0]*6+3000; a[80,1]=a[80,1]*2
go('low + reject at 80',lowrej)
def lowrej2(a,s2,t2): low(a,s2,t2); a[700,0]=a[700,0]*6+3000; a[700,1]=a[700,1]*2
go('low + reject at 700',lowrej2)
def lowgap(a,s2,t2): low(a,s2,t2); s2[300:]+=5; t2[300:]+=1500
go('low + seq gap at 300',lowgap)
def lowgapbig(a,s2,t2): low(a,s2,t2); s2[300:]+=50; t2[300:]+=15000
go('low + seq gap 50 at 300',lowgapbig)
