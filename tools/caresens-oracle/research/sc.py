import sys, os; sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
assert os.environ.get('CARESENS_REFERENCE_LIB'), 'set CARESENS_REFERENCE_LIB to the arm64 libCALCULATION.so'
import scenarios, make_fixtures as mf, caresens_oracle as co, numpy as np
REF=co.Reference()
def runspec(spec,snap=False):
    seq,t,adc,T=scenarios.generate(**spec); full=dict(scenarios.DEFAULTS,**spec); d=co.default_device()
    for k in ('slope100','vref','eapp'): d[k][0]=full[k]
    c,o,g,a,s=REF.run(d,co.records(seq,t,np.array(adc,'u2'),T),snapshots=snap)
    return np.array(seq),np.array(t),np.array(adc),np.array(T),c,o[:,0],g[:,0],s,d
