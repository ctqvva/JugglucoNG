from sc import *
import differential as df
spec={'seed': 61012774, 'readings': 800, 'start': 1, 'slope100': 4.0953521805098205, 'vref': 1.549006997775988, 'eapp': 0.0765554074090471, 'lo': 90, 'hi': 440, 'knot_min': 3, 'noise': 5, 'temp_mode': 'vary', 'temp_lo': 3166, 'decay': 0.5, 'gap_permille': 100, 'gap_max': 3, 'jitter': 5, 'spike_permille': 20, 'clock_jump_at': 200, 'clock_jump': 2000, 'knot_max': 30, 'temp_hi': 3466}
seq,t,adc,T,c,o,g,s,d=runspec(spec,True)
recs=co.records(seq,t,np.array(adc,'u2'),T)
jar=df.engine_jar()
full=dict(scenarios.DEFAULTS,**spec); P=(full['slope100'],full['vref'],full['eapp'])
k0=df.run_engine(jar,P,recs)
for st in (572,573,574):
    k=df.run_engine(jar,P,recs[st:],s[st-1:st])
    z=0
    print(st,'seq',seq[st],'dt',t[st]-t[st-1],'dseq',seq[st]-seq[st-1],'fresh diff',np.abs(k0['minutes'][st]-g['tran_inA_1min'][st]).max(),'import diff',np.abs(k['minutes'][0]-g['tran_inA_1min'][st]).max())
print('seq',seq[566:577],'dt',np.diff(t[565:577]))
i=573
print('flags',s['outlier_max_index'][i] if 'outlier_max_index' in s.dtype.names else None)
print([n for n in s.dtype.names if 'outlier' in n or 'prev' in n][:40])
for i in (572,573,574):
    print(i,'type',o['data_type'][i-1:i+2],'seq_prev',s['seq_prev'][i-1],'time_prev',s['time_prev'][i-1],'t',t[i-1],'idx',s['idx'][i-1] if 'idx' in s.dtype.names else '?','contact',s['prev_contact_err_algo_seq'][i-1])
k=df.run_engine(jar,P,recs[574:],s[573:574])
print('engine type',k['type'][:3],'k0 type',k0['type'][573:577])
print('minutes vend',g['tran_inA_1min'][574],'fresh',k0['minutes'][574],'imp',k['minutes'][0])
