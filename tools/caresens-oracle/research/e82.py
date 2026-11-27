from ref import *
from gen import mkwo
np.set_printoptions(linewidth=200,precision=6,suppress=True)
R,r,o,g,s=compare('noise300',mkwo(400,1,noise=300)); 
for i in (306,307,308,309): print(i+1,'res',g['out_rescale'][i],'ad',g['opcal_ad'][i],'wt',g['out_weight_ad'][i],g['shiftout_ad'][i],'glu',o['result_glucose'][i],'K',s['out_kalman'][i],'dt',o['data_type'][i],'S',s['smooth_sig_in'][i][-3:],'smo',o['smooth_result_glucose'][i][-2:])
def trs(nm,wo,k=8,**kw):
    R,r,o,g,s=compare(nm,wo,**kw); S=s['smooth_sig_in']; m=(S[:,9]-S[:,3])/30; t=o['trendrate']; b=np.nonzero(np.abs(m-t)>1e-9)[0]; b=b[b>11]
    for i in b[:k]: print(i+1,'lib',t[i],'mine',m[i],'S',S[i][3:],'t',(s['smooth_time_in'][i].astype(int)-BASE)[[0,3,9]],'seq',o['seq_number_final'][i], 'K2',s['out_kalman'][i],'roc',s['kalman_roc'][i])
    print(len(b)); return o,g,s,b
trs('wide',mkwo(1200,2,40,400)); trs('lev30',mkwo(500,1,level=30),4); trs('noise',mkwo(300,1,noise=300),6)
