#include "datbackup.hpp"

extern Backup *backup;
void showupdate(const  struct updateone* dat) {
   LOGGER("ind=%d allindex=%d nums=%d scans=%d stream=%d\n",dat->ind,dat->allindex,dat->sendnums,dat->sendscans,dat->sendstream);
   }
void showbackup() {
    LOGAR("showbackup");
   const struct updatedata *update=backup->getupdatedata();
   LOGGER("hostnr=%d receive=%d port=%s sendnr=%d\n",update->hostnr,update->receive,update->port,update->sendnr);
   for(int i=0;i<update->hostnr;i++) {
      const auto &el=update->allhosts[i];
      LOGGER("%s %s nr=%d index=%d\n", el.getnameif(), el.wearos?"WearOS":"",el.nr,el.index);
      if(el.index>=0) {
	   showupdate(update->tosend+el.index);
	   }
      }
   }

/*
2024-11-19-18:12:06 16526 hostnr=1 receive=0 port=8795 sendnr=4
2024-11-19-18:12:06 16526 c30c9cf0 WearOS nr=1 index=0
2024-11-19-18:12:06 16526 ind=4 allindex=1 nums=1 scans=1 stream=1
*/

