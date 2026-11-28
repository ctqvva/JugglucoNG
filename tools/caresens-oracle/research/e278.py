from sc import *
import json
cs=json.load(open('c0specs.json'))
for ri in (2,7,10):
    seq,t,adc,T,c,o,g,s,d=runspec(cs[ri],True)
    D=g['err4_delay_condi']
    i0=int(np.nonzero(seq>=2010)[0][0])
    for i in range(i0,min(i0+90,len(seq)),6):
        print(ri,i,'seq',seq[i],'idx',s['idx'][i],'D',D[i].tolist(),'err',o['errcode'][i],'type',o['data_type'][i],'mdc_first',s['mdc_first_flag'][i],'mdc_start',s['mdc_start_idx'][i],'cnt',s['sum_curr_cnt_for_mdc'][i],'level',s['diabetes_level_diabetes'][i])
