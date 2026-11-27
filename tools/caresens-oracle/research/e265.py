from craft import *
seq,t,adc,T=base(); n=700
seq,t,adc,T=seq[:n],t[:n],adc[:n].copy(),T[:n]
adc[500:520]=adc_for(0.2)
def go(keep,label,mod=None):
    s2,t2,a2,T2=seq[keep],t[keep],adc[keep].copy(),T[keep]
    if mod: mod(a2)
    cc,o,g,_,s=REF.run(DEV,co.records(s2,t2,a2.astype('u2'),T2),snapshots=True); o=o[:,0]; g=g[:,0]
    C=g['err4_condi'][:,0]; first=np.nonzero(C)[0][:1]
    print(label,'first C0 at seq',s2[first].tolist(),'err4 at seq',s2[np.nonzero(o['errcode']&4)[0][:1]].tolist(),'types',o['data_type'][np.searchsorted(s2,500):np.searchsorted(s2,500)+6].tolist())
idx=np.arange(n)
go(idx,'no gap')
go(idx[(idx<501)|(idx>502)],'gap seq 502-503 (records 501,502 removed)')
go(idx[(idx<498)|(idx>499)],'gap before plateau (498,499 removed)')
go(idx[(idx<495)|(idx>499)],'gap 5 before plateau')
def rej(a):  # contact-like spike in first samples of record index 502 (in kept array)
    a[502,0]=a[502,0]*6+3000; a[502,1]=a[502,1]*2
go(idx,'contact spike at 502',rej)
