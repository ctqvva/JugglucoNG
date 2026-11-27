exec(open('e180.py').read().split("jar=df")[0])
nm=[n for n in s.dtype.names if 'buff' in n or 'err32' in n]
print(nm)
for i in range(196,204): print(i,'seq',seq[i],'t',t[i]-t[i-1],'idx',s['idx'][i],{n:s[n][i].tolist() for n in nm}, 'type',o['data_type'][i])
