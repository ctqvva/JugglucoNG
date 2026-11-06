# iCan RU — офлайн-расшифровка логов хука, 2026-11-04

## Метод
Скрипт [tools/ican-capture/lsp_decrypt.py](../../tools/ican-capture/lsp_decrypt.py) разбирает дампы logcat
(`ICANX`), восстанавливает чанки (`#N`), связывает `CIPHER-INIT` → `CIPHER-IN`/`CIPHER-OUT`,
RSA-обёртку ↔ `key=` URL (equality hex), AES-запрос ↔ ответ по совпадению `keyHex`.
Источники: `logcat-2026-11-04-v3.log`, `logcat-2026-11-04-v4.log`, live-стрим v4 (17:35…18:09).
Полные материала — `/tmp/opencode/ican-ce/lsp/analyze.json` (локально, в репо не включается).

## Главные факты
1. **Ключ ответа = ключ запроса** (би-дирекционально один ASCII-ключ):
   - ENC 17:44:51.060 keyHex=`3533 55…` → DEC 17:44:51.540 тем же ключом (`user-device/page`);
   - ENC 17:44:51.581 keyHex=`6931 6a…` → DEC 17:44:51.670 (`user-apparatus/bind/info`);
   - В ответе сервер НЕ присылает новый ключ — `createEncryptedResponse` переиспользует запросный.
   Отсюда простая реализация JNG: отправил `key=` — тем же ключом (IV=key[:16]) срезаешь паддинг ответа.
2. **Ключ конверта живёт «окном» ~2–4 с, не «запросом»**
   один и тот же 32-ASCII ключ наблюдается в `CIPHER-IN` RSA двух разных процессов (17:44:53.426 pid 9884
   и 17:44:55.185 pid 10008) и у нескольких запросов подряд. Для JNG неважно (генерим свой на вызов —
   сервер принимает), но факт: сервер кеширует/принимает переиспользование.
3. Формат паддинга тела запроса — ручной PKCS7 в живых данных также в пустых телах:
   `{}` + `0x1e`×30; `{"country":"RUS"}` + `0x0f`×15; `{"current":"1","size":"999"}` + `0x04`×4.

## Запросы (plaintext подтверждён)
| endpoint | method | body |
|---|---|---|
| `sino-health/v1/user-device/page` | GET | `{"current":"1","size":"999"}` |
| `sino-cgm/v1/user-apparatus/bind/info` | GET | `{}` |
| `sino-cgm/v1/user/get-user-info` | GET | `{}` |
| `sino-cgm/v1/country/setting/detail` | GET | `{"country":"RUS"}` |
| `sino-cgm/v1/country/setting/detail/device` | GET | `{"country":"RUS","device":"i3"}` |
| `sino-cgm/v1/app-version/upgrade/info` | POST | `{}` |
| `sino-medical/v1/invite/unread-count` | GET | `{}` (частично; тело не поймано) |

Ограничение: часть линий BUILDER на каждый(endpoint) идут парами «до-конверт»/«после-конверт»
(`newCall`-хук фиксирует запрос до интерсептора, `Request$Builder.build()` — финальный).

## Ответы (расшифрованы офлайн, тождественно хуку)
### `user-device/page` — 1248 байт, полный
```
{"code":200,"success":true,"data":{"records":[
 {"id":"<recordId A>","status":2,"userId":"<userId>","deviceSn":"<sensor SN1>","mac":"<BLE MAC 1>",
  "productCode":"100295","productName":"i3","productTypeId":"18","productProtocol":"ble",
  "productPictures":"https://storage.yandexcloud.net/cgmru1/public/cgmProductPic/i3.png",
  "dataProtocolCode":"sino_standard_cgm_ble","machineCode":"","bleNamePrefix":"SN-A",
  "hisDataFlag":0,"timeZone":"Europe/Moscow","detectionStartTime":1790446125273,
  "detectionEndTime":1791742125000,"boundTime":null,"useUserId":null},
 {"id":"<recordId B>","status":2,"userId":"<userId>","deviceSn":"<sensor SN2>","mac":"<BLE MAC 2>", … } ],
 "total":2,"size":500,"current":1,"pages":1,"orders":[],"optimizeCountSql":true,
 "hitCount":false,"searchCount":true},"msg":"操作成功"}
```
Данные: запросили `size=999` — сервер вернул `size:500` (кап пагинации сервера; JNG должен читать `size`).
`msg: "操作成功"` — фон-локаль сервера.

### `user-apparatus/bind/info` — 576 байт, полный
```
{"code":200,"success":true,"data":{
 "id":"<recordId>","userId":"<userId>","apparatusSn":"<sensor SN1>","apparatusMacAddr":"<BLE MAC 1>",
 "apparatusBindTime":"2026-09-27 02:05:57","detectionStartTime":1790446125273,
 "detectionEndTime":1791742125000,"deviceStatus":2,"productCode":"100295","productName":"i3",
 "deviceGoodsType":1,"imageUrl":"…","timezone":"Europe/Moscow","shareHcp":null,"calibrateBgmList":[]},
 "msg":"Операция выполнена успешно"}
```
Новый endpoint (в DART-RECON отсутствовал). Вероятная роль — активная привязка
«пациент↔аппарат» для экрана калибровок (`calibrateBgmList` пуст до активации).

### `app-version/upgrade/info` — 128 байт
```
{"code":200,"success":true,"data":null,"msg":"Операция выполнена успешно","ts":…}
```

### `user/get-user-info` — 9728 байт, обрезан хуком (>4096 → `hex0` первые 64 байта)
```
{"code":200,"success":true,"data":{"id":"<userId>","firstName":null, "lastName":… (дальше урезано)
```
Надо повторить при улучшении хука v5 (dump OUT >4KB в файл на устройстве).

### прочее
`country/setting/detail?country=RUS`, `country/setting/detail/device`, `invite/unread-count` —
запросы пойманы (с plaintext и ключом), ответы в окне наблюдения не расшифрованы
(процессы умирали до прихода ответа / ответов не было).

## Что добавить в v5-хук
- dump `CIPHER-OUT`/`CIPHER-IN` >4096 байт в файл на устройстве (`/data/local/tmp/ican-<ts>.bin`), hex-линия сохраняет лишь 64 байта;
- включить GCM-операции по флагу (сейчас отфильтрованы);
- дампить тело ответа по HTTP-`Response` (по аналогии с `BUILDER`) — для полей `key`/`data` в ответах.