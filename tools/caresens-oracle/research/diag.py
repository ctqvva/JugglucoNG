from sc import *
from kt import kt
np.set_printoptions(linewidth=230,precision=5,suppress=True)
def diag(spec,ctx=3):
    seq,t,adc,T,c,o,g,s,d=runspec(spec,True); k=kt(d,adc.astype('u2'),T,seq,t)
    ok=(o['data_type']!=2)
    for nm,a_,b_ in (('one',None,None),('corr',k['corr'],g['out_iir']),('cg',k['cg'],g['init_cg']),('res',k['res'],g['out_rescale']),('glu',k['g'],o['result_glucose'])):
        if a_ is None: continue
        dd=np.abs(a_-b_)*ok
        if (dd>1e-7).any():
            q=int(np.argmax(dd>1e-7)); print('first diverging stage',nm,'at record',q+1,'seq',seq[q])
            for z in range(max(0,q-ctx),q+2): print('  ',z+1,'seq',seq[z],'dt',t[z]-t[z-1] if z else 0,'dtype',o['data_type'][z],'err',o['errcode'][z],nm,'mine %.6f lib %.6f'%(a_[z],b_[z]),'res %.4f/%.4f'%(k['res'][z],g['out_rescale'][z]),'K',s['out_kalman'][z],'roc',s['kalman_roc'][z],'w',s['warmup_cnt'][z],'st',s['state_init_kalman'][z],s['kalman_state_fluctuation'][z])
            return seq,t,adc,T,c,o,g,s,d,k,q
    print('no divergence')
if __name__=='__main__':
    import json,sys
    diag(json.loads(sys.argv[1]))
