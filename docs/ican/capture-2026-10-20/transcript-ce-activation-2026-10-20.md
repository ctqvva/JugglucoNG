# iCan CE activation — ATT transcript (sensor link)

Sensor link only (ACL handle `0x0201`); unrelated BLE links are excluded.
Serial-like ASCII runs are masked as `<SN>`; no MACs or account ids are present.
Time is relative to the first ATT PDU in the exported window.

| t+ (s) | dir | ATT | handle | payload | note |
|---|---|---|---|---|---|
| 1.656 | H→S | Read Request | `0x0013 ` |  |  |
| 1.723 | S→H | Read Response | `-` | `00 00` |  |
| 1.736 | H→S | Write Request | `0x0025 SSCP CCCD` | `02 00` |  |
| 1.745 | S→H | Write Response | `-` |  |  |
| 1.750 | H→S | Write Request | `0x0017 Measurement CCCD` | `01 00` |  |
| 1.888 | S→H | Write Response | `-` |  |  |
| 1.893 | H→S | Write Request | `0x0022 RACP CCCD` | `02 00` |  |
| 2.084 | S→H | Write Response | `-` |  |  |
| 2.089 | H→S | Read Request | `0x0028 ` |  |  |
| 2.132 | S→H | Read Response | `-` | `69 33` |  |
| 2.137 | H→S | Write Request | `0x0024 SSCP` | `f3` | auth: request challenge |
| 2.181 | S→H | Write Response | `-` |  |  |
| 2.278 | S→H | Indicate | `0x0024 SSCP` | `06 f3 01 c8 41 2f 28` | auth: challenge c8412f28 |
| 2.278 | H→S | Confirm | `-` |  |  |
| 2.282 | H→S | Read Request | `0x002C Serial Number` |  |  |
| 2.571 | S→H | Read Response | `-` | `<SN>` |  |
| 2.571 | H→S | Read Blob Request | `0x002C Serial Number` | `16 00` |  |
| 2.717 | S→H | Read Blob Response | `-` | `30 30` |  |
| 2.723 | H→S | Write Request | `0x0024 SSCP` | `f4 10 99 01 68 e0 d1 41 73 85 68 e7 a6 b7 55 ab 98 8a` | auth: token (16 bytes) |
| 2.766 | S→H | Write Response | `-` |  |  |
| 2.814 | S→H | Indicate | `0x0024 SSCP` | `1c f4 01` | auth: token accepted |
| 2.815 | H→S | Confirm | `-` |  |  |
| 2.836 | H→S | Write Request | `0x0024 SSCP` | `f6` | sensor info request (F6) |
| 3.009 | S→H | Write Response | `-` |  |  |
| 3.058 | S→H | Indicate | `0x0024 SSCP` | `1c f6 01 ff ff` | sensor info: codeState=0xFFFF (UNPROVISIONED) |
| 3.058 | H→S | Confirm | `-` |  |  |
| 3.209 | H→S | Read Request | `0x002A ` |  |  |
| 3.399 | S→H | Read Response | `-` | `69 43 47 4d 2d 74 33` |  |
| 3.406 | H→S | Read Request | `0x002E ` |  |  |
| 3.789 | S→H | Read Response | `-` | `41 2e 32 00 00 00 00 00 00 00 00 00 00 00 00 00` |  |
| 3.793 | H→S | Read Request | `0x0030 ` |  |  |
| 3.887 | S→H | Read Response | `-` | `33 2e 32 2e 38 00 00 00 00 00 00 00 00 00 00 00` |  |
| 3.892 | H→S | Read Request | `0x0032 ` |  |  |
| 4.179 | S→H | Read Response | `-` | `56 30 31 2e 30 32 2e 30 31 2e 30 30 5f 42 30 30 30 31` |  |
| 4.186 | H→S | Read Request | `0x0035 ` |  |  |
| 4.520 | S→H | Read Response | `-` | `64 cc 91 16 00` |  |
| 4.524 | H→S | Read Request | `0x001B ` |  |  |
| 4.959 | S→H | Read Response | `-` | `00 00 01 00 00 00` |  |
| 4.963 | H→S | Read Request | `0x001D Session Start Time` |  |  |
| 5.154 | S→H | Read Response | `-` | `00 00 00 00 00 00 00 00 00` |  |
| 9.552 | H→S | Write Request | `0x0024 SSCP` | `1a` | start session (0x1A) |
| 9.630 | S→H | Write Response | `-` |  |  |
| 16.521 | H→S | Read Request | `0x0013 ` |  |  |
| 16.543 | S→H | Read Response | `-` | `00 00` |  |
| 16.554 | H→S | Write Request | `0x0025 SSCP CCCD` | `02 00` |  |
| 16.559 | S→H | Write Response | `-` |  |  |
| 16.563 | H→S | Write Request | `0x0017 Measurement CCCD` | `01 00` |  |
| 16.603 | S→H | Write Response | `-` |  |  |
| 16.610 | H→S | Write Request | `0x0022 RACP CCCD` | `02 00` |  |
| 16.660 | S→H | Write Response | `-` |  |  |
| 16.664 | H→S | Read Request | `0x0028 ` |  |  |
| 16.855 | S→H | Read Response | `-` | `69 33` |  |
| 16.860 | H→S | Write Request | `0x0024 SSCP` | `f3` | auth: request challenge |
| 16.903 | S→H | Write Response | `-` |  |  |
| 16.937 | H→S | Write Request | `0x0024 SSCP` | `1a` | start session (0x1A) |
| 16.952 | S→H | Indicate | `0x0024 SSCP` | `06 f3 01 0c 02 a8 e4` | auth: challenge 0c02a8e4 |
| 16.952 | H→S | Confirm | `-` |  |  |
| 16.958 | H→S | Read Request | `0x002C Serial Number` |  |  |
| 17.050 | S→H | Read Response | `-` | `<SN>` |  |
| 17.050 | H→S | Read Blob Request | `0x002C Serial Number` | `16 00` |  |
| 17.196 | S→H | Read Blob Response | `-` | `30 30` |  |
| 17.200 | H→S | Write Request | `0x0024 SSCP` | `f4 10 4d 84 9c b0 87 90 9d 81 11 ea f6 ac ed 1b e0 0a` | auth: token (16 bytes) |
| 17.245 | S→H | Write Response | `-` |  |  |
| 17.294 | S→H | Indicate | `0x0024 SSCP` | `1c f4 01` | auth: token accepted |
| 17.295 | H→S | Confirm | `-` |  |  |
| 17.301 | H→S | Write Request | `0x0024 SSCP` | `f6` | sensor info request (F6) |
| 17.342 | S→H | Write Response | `-` |  |  |
| 17.391 | S→H | Indicate | `0x0024 SSCP` | `1c f6 01 ff ff` | sensor info: codeState=0xFFFF (UNPROVISIONED) |
| 17.391 | H→S | Confirm | `-` |  |  |
| 17.541 | H→S | Read Request | `0x002A ` |  |  |
| 17.635 | S→H | Read Response | `-` | `69 43 47 4d 2d 74 33` |  |
| 17.639 | H→S | Read Request | `0x002E ` |  |  |
| 17.683 | S→H | Read Response | `-` | `41 2e 32 00 00 00 00 00 00 00 00 00 00 00 00 00` |  |
| 17.687 | H→S | Read Request | `0x0030 ` |  |  |
| 17.927 | S→H | Read Response | `-` | `33 2e 32 2e 38 00 00 00 00 00 00 00 00 00 00 00` |  |
| 17.930 | H→S | Read Request | `0x0032 ` |  |  |
| 18.024 | S→H | Read Response | `-` | `56 30 31 2e 30 32 2e 30 31 2e 30 30 5f 42 30 30 30 31` |  |
| 18.027 | H→S | Read Request | `0x0035 ` |  |  |
| 18.122 | S→H | Read Response | `-` | `64 60 e3 16 00` |  |
| 18.127 | H→S | Read Request | `0x001B ` |  |  |
| 18.219 | S→H | Read Response | `-` | `00 00 01 00 00 00` |  |
| 18.222 | H→S | Read Request | `0x001D Session Start Time` |  |  |
| 18.415 | S→H | Read Response | `-` | `00 00 00 00 00 00 00 00 00` |  |
| 29.834 | H→S | Read Request | `0x0013 ` |  |  |
| 29.856 | S→H | Read Response | `-` | `00 00` |  |
| 29.866 | H→S | Write Request | `0x0025 SSCP CCCD` | `02 00` |  |
| 29.871 | S→H | Write Response | `-` |  |  |
| 29.877 | H→S | Write Request | `0x0017 Measurement CCCD` | `01 00` |  |
| 29.886 | S→H | Write Response | `-` |  |  |
| 29.891 | H→S | Write Request | `0x0022 RACP CCCD` | `02 00` |  |
| 29.908 | S→H | Write Response | `-` |  |  |
| 29.910 | H→S | Read Request | `0x0028 ` |  |  |
| 30.118 | S→H | Read Response | `-` | `69 33` |  |
| 30.122 | H→S | Write Request | `0x0024 SSCP` | `f3` | auth: request challenge |
| 30.167 | S→H | Write Response | `-` |  |  |
| 30.264 | S→H | Indicate | `0x0024 SSCP` | `06 f3 01 0e 3f 90 f0` | auth: challenge 0e3f90f0 |
| 30.264 | H→S | Confirm | `-` |  |  |
| 30.268 | H→S | Read Request | `0x002C Serial Number` |  |  |
| 30.314 | S→H | Read Response | `-` | `<SN>` |  |
| 30.314 | H→S | Read Blob Request | `0x002C Serial Number` | `16 00` |  |
| 30.362 | S→H | Read Blob Response | `-` | `30 30` |  |
| 30.366 | H→S | Write Request | `0x0024 SSCP` | `f4 10 9f 46 17 10 86 c9 b5 66 6c c6 0e 93 cd 01 fd 65` | auth: token (16 bytes) |
| 30.557 | S→H | Write Response | `-` |  |  |
| 30.558 | S→H | Indicate | `0x0024 SSCP` | `1c f4 01` | auth: token accepted |
| 30.558 | H→S | Confirm | `-` |  |  |
| 30.563 | H→S | Write Request | `0x0024 SSCP` | `f6` | sensor info request (F6) |
| 30.606 | S→H | Write Response | `-` |  |  |
| 30.654 | S→H | Indicate | `0x0024 SSCP` | `1c f6 01 ff ff` | sensor info: codeState=0xFFFF (UNPROVISIONED) |
| 30.655 | H→S | Confirm | `-` |  |  |
| 30.804 | H→S | Read Request | `0x002A ` |  |  |
| 30.849 | S→H | Read Response | `-` | `69 43 47 4d 2d 74 33` |  |
| 30.853 | H→S | Read Request | `0x002E ` |  |  |
| 30.899 | S→H | Read Response | `-` | `41 2e 32 00 00 00 00 00 00 00 00 00 00 00 00 00` |  |
| 30.902 | H→S | Read Request | `0x0030 ` |  |  |
| 30.947 | S→H | Read Response | `-` | `33 2e 32 2e 38 00 00 00 00 00 00 00 00 00 00 00` |  |
| 30.950 | H→S | Read Request | `0x0032 ` |  |  |
| 30.996 | S→H | Read Response | `-` | `56 30 31 2e 30 32 2e 30 31 2e 30 30 5f 42 30 30 30 31` |  |
| 30.999 | H→S | Read Request | `0x0035 ` |  |  |
| 31.093 | S→H | Read Response | `-` | `64 88 86 16 00` |  |
| 31.097 | H→S | Read Request | `0x001B ` |  |  |
| 31.191 | S→H | Read Response | `-` | `00 00 01 00 00 00` |  |
| 31.194 | H→S | Read Request | `0x001D Session Start Time` |  |  |
| 31.337 | S→H | Read Response | `-` | `00 00 00 00 00 00 00 00 00` |  |
| 38.024 | H→S | Write Request | `0x0024 SSCP` | `1a` | start session (0x1A) |
| 38.544 | S→H | Write Response | `-` |  |  |
| 38.544 | S→H | Indicate | `0x0024 SSCP` | `1c 1a 01` | session started |
| 38.545 | H→S | Confirm | `-` |  |  |
| 38.558 | H→S | Write Request | `0x001D Session Start Time` | `ea 07 0a 14 0e 17 27 14 00` | session start time 2026-10-20 14:23:39 tz=20 dst=0 |
| 39.188 | S→H | Write Response | `-` |  |  |
| 39.206 | H→S | Read Request | `0x001B ` |  |  |
| 39.834 | S→H | Read Response | `-` | `00 00 00 00 00 00` |  |
| 39.867 | H→S | Read Request | `0x001D Session Start Time` |  |  |
| 40.478 | S→H | Read Response | `-` | `ea 07 0a 14 0e 17 27 14 00` |  |
| 41.586 | H→S | Write Request | `0x0024 SSCP` | `f2 10 b1 f2 ba 39 6d 9d 92 67 e0 53 c9 e1 49 3a 95 27` | F2 chunk (16 bytes) |
| 41.984 | S→H | Write Response | `-` |  |  |
| 42.091 | S→H | Indicate | `0x0024 SSCP` | `1c f2 01` | F2 chunk ack |
| 42.091 | H→S | Confirm | `-` |  |  |
| 42.100 | H→S | Write Request | `0x0024 SSCP` | `f2 10 4e a5 96 4c 76 52 73 35 9f 4b 53 fc 68 66 d7 06` | F2 chunk (16 bytes) |
| 42.629 | S→H | Write Response | `-` |  |  |
| 42.736 | S→H | Indicate | `0x0024 SSCP` | `1c f2 01` | F2 chunk ack |
| 42.737 | H→S | Confirm | `-` |  |  |
| 42.746 | H→S | Write Request | `0x0024 SSCP` | `f2 10 2c 56 21 5a ab f7 a9 33 6e 48 01 f5 00 68 3c 5e` | F2 chunk (16 bytes) |
| 43.274 | S→H | Write Response | `-` |  |  |
| 43.918 | S→H | Indicate | `0x0024 SSCP` | `1c f2 01` | F2 chunk ack |
| 43.919 | H→S | Confirm | `-` |  |  |
| 43.926 | H→S | Read Request | `0x001B ` |  |  |
| 44.563 | S→H | Read Response | `-` | `00 00 00 00 00 00` |  |
| 44.571 | H→S | Write Request | `0x0021 RACP` | `f1 01 01` | RACP report records |
| 45.209 | S→H | Write Response | `-` |  |  |
| 45.209 | S→H | Indicate | `0x0021 RACP` | `06 00 f1 06` |  |
| 45.209 | H→S | Confirm | `-` |  |  |

# rows: 151
