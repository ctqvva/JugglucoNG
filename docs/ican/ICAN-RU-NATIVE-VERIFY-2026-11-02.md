# iCan Health RU — снятие дампа и кэша (2026-11-02)

Устройство: Redmi rosemary (`<device serial>`), root, RU-апп `com.sinocare.ican.health.ru` (01.16.01.02, vc30), PIDs 24771 (main) / 24893 (child).
Причина: сенсор не обнаружен (`ScanTimeout`), активацию/выгрузку снять нельзя.

## OkHttp-кэш (`/data/data/.../cache/OkHttpCache`)
60 записей, все с зашифрованным `?key=&data=`:
- `sino-health/v1/user-device/detail` — 1
- `sino-cgm/v1/user/get-user-info` — 13
- `sino-cgm/v1/basic/config/detail` — 13
- `sino-medical/v1/invite/unread-count` — 34

## Дамп памяти (`/data/local/tmp/ru-dump/{24771,24893}`)
- 24771: 689 регионов, 1.4 ГБ; 24893: 279 регионов, 735 МБ.
- **Plaintext JSON** ответов `user-device/detail` и `user-device/page`:
  - `<sensor SN>` (i3, productCode 100295), `detectionStartTime=1790446125273`, `detectionEndTime=1791742125000`;
  - `<sensor SN>` (i3), `detectionStartTime=1777970980000`, `detectionEndTime=1779266980000`;
  - поля: `productCode`, `productName`, `dataProtocolCode:"sino_standard_cgm_ble"`, `bleNamePrefix:"SN-A"`, `timeZone:"Europe/Moscow"`, `userId`.
- **Схема нативной Room-БД `SdkCgmDeviceInfo`**: `correctCode TEXT`, `hasWriteCorrectCode`, `launcherState`, `usageDaysLimit`, `hasGluDataCompleted`, `hasOriginalDataCompleted`, `intervalTime`, `initTime`, `batteryLevel`, `launcherVersion/Name/Model`, `hardwareVersion`, `firmwareVersion`, `userId`.
- **Эндпоинты**: `original/bind|code|data/save|default/parameter`, `user-apparatus/save|page|detail|update|bind/info`, `detection-data/add|batch-add|page|latest-one|list-serial-number`, `user-device/detail|page|bind|update|batch-remove|batch-unbind|check-device-bind|update-connection-time`, `follower/save`, `invite/unread-count`.
- **SDK-логи** (батчи `{"events":[...],"phoneId":...,"type":2}`): `cgm_start_scan`, `cgm_scan_result` (`ScanTimeout`), `cgm_bluetooth_state_change`, `cgm_bluetooth_connect_info`; `cgmSdkCoreVersion:01.02.02.03`, `cgmSdkLogVersion:1.2.2.1`.

## Базы данных (`/data/data/.../databases`)
- `cgm_sdk.db` — **SQLCipher** (`net.sqlcipher`, `libsqlcipher.so`, `SupportFactory`). Passphrase передаётся через `SupportFactory` и после открытия вычищается из памяти (`clearPassphrase`). Простые кандидаты (`sinocare`, `cgm_sdk`, package, userId, phoneId, android_id, serial, их md5/sha1/sha256, raw-hex) не подошли. Заголовок dex в дампе вытерт, поэтому пароль из кода так быстро не достать.
- `sinocare_log` — plaintext SQLite, таблица `LogsEventBean` (1 строка: `cgm_scan_result`/ScanTimeout). Логи батчатся (`{"events":[...],"type":2}`) и, вероятно, выгружаются; но события активации/калибровки должны попадать сюда.
- `B949532EDCA0B1A8` — Room-БД Flutter-слоя (WAL 53 КБ).

## Выводы
- Облачный контракт `user-device/*` подтверждён; ответы SDK расшифровываются и живут в памяти plaintext — можно искать payload запросов.
- Родная калибровка: `cgm_sdk.db.correctCode` (SQLCipher, пароль пока не найден), в логах называется `writeCorrectCode`/`hasWriteCorrectCode`.
- Сенсор не рекламируется, поэтому активацию/калибровку/выгрузку сейчас не снять.

## Следующее
1. ~~Расшифровать `cgm_sdk.db`~~ — SQLCipher, пароль не восстановлен офлайн. Альтернативы: HCI (F2-чанки 3×18), `sinocare_log` (plaintext-события), дамп памяти в момент калибровки.
2. Завтра активировать новый сенсор и ловить: HCI, `sinocare_log`, дамп памяти, OkHttp-кэш.
