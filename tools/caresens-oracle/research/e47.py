from gen import *
np.set_printoptions(linewidth=220,precision=9,suppress=True)
N=4320; wo=mkwo(N,1); r,o,g,a,s=run(dev(),wo[:700],34.,snap=True)
inv=(g['out_iir']/g['out_drift']); sq=np.arange(1,701.); c=np.polyfit((sq)/1000,inv,3); print(['%.15g'%x for x in c])
k=g['out_rescale']/g['init_cg']; d=g['curr_baseline']
ch=np.nonzero(np.abs(np.diff(k))>1e-12)[0]+2; print('seqs where ratio changes',ch[:80],len(ch))
for i in (0,1,2,15,16,17,18,19,20,30,60,100,200,287,288,289,400,500,570,574,575,576,577):
    print(i+1,'k-1 %.9f'%(k[i]-1),'base %.6f dc %.6f drift %.5f'%(g['curr_baseline'][i],g['initstable_diff_dc'][i],g['out_drift'][i]),'cnt',g['initstable_initcnt'][i],'endp',g['initstable_init_end_point'][i],'w',g['initstable_weight_usercal'][i],g['initstable_weight_nocal'][i],g['initstable_fixusercal'][i],'args',s['initstable_mean_dc'][i],s['initstable_diff_dc'][i],s['baseline_prev'][i],s['sum_curr_for_init_value'][i],s['sum_curr_cnt_for_init_value'][i],s['sum_curr_for_mdc'][i],s['sum_curr_cnt_for_mdc'][i],s['mdc_start_idx'][i],s['mdc_first_flag'][i],s['iir_use_flag'][i],s['iir_start_flag'][i])
