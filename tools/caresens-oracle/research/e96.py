import numpy as np
obs=np.array([.1627,-1.04125,.80795,.07135])/100
for nd in ([-250,-150,-50,350],[-245,-145,-45,355],[-250,-150,-50,355],[-255,-155,-55,345],[-240,-140,-40,350]):
    x=np.array(nd,float)
    for deg in (2,3):
        S=[]
        for i in range(4):
            y=np.zeros(4);y[i]=1; c=np.polyfit(x,y,deg); S.append(np.polyval(np.polyder(c),nd[2]))
        print(nd,deg,np.array(S)/obs)
