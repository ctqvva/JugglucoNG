# iCan activation capture — runbook (2026-11-03)

Устройство: Redmi rosemary (root). Пакет: `com.sinocare.ican.health.ru`.
Цель: активировать новый сенсор и снять HCI + plaintext-логи SDK + дамп/кэш.

## 0. Перед активацией
- Убедиться, что HCI snoop включён: `adb shell su -c "settings get secure bluetooth_hci_log"` → `1`.
- Проверить, что файл HCI растёт: `adb shell su -c "ls -l /proc/$(pidof com.android.bluetooth)/fd | grep -i cfa"`.
- Зафиксировать время старта: `date +%s` (локально и на устройстве).
- Снять baseline-снимок: `collect_snapshot.sh before`.

## 1. Активация
1. Открыть приложение, активировать новый сенсор (или переактивировать).
2. Во время активации (параллельно) запустить быстрый снимок: `collect_snapshot.sh during`.
3. После завершения активации: `collect_snapshot.sh after`.
4. Если нужна память — `collect_snapshot.sh after mem` (≈1.4 ГБ на pid, остаётся в `/data/local/tmp/ru-dump/<pid>`).

## 2. Разбор
- HCI: `hci_transcript.py <hci.pcap> --out transcript.md --after <epoch>` (auth редактируется).
- SDK-логи: `sqlite3 db/sinocare_log "SELECT id,identifier,properties FROM LogsEventBean ORDER BY id DESC LIMIT 200;"` — искать `writeCorrectCode`, `parameterCode`, `hasWriteCorrectCode`.
- OkHttp: `okhttp_resp.sh` (URL + gzip-тело) — искать `user-apparatus/save`, `original/data/save`, `original/default/parameter`, `detection-data/*`.
- Память: `grep -a` по `/data/local/tmp/ru-dump/<pid>/*.bin` для `SdkCgmDeviceInfo`, `correctCode`, `parameterCode`, `detectionStartTime`.

## 3. Что считать успехом
- HCI: `F3`/`F4`/`F6`/`0x1A` + 3×18-байтных F2-блока калибровки.
- `sinocare_log`: событие `writeCorrectCode` с кодом/таблицей.
- OkHttp: payload `user-apparatus/save`/`original/data/save`.
- Память: entity `SdkCgmDeviceInfo` с `correctCode`.

## 4. Гигиена данных
- Сырые HCI/дампы/кэш — только вне репозитория (`/tmp/ican-snaps`).
- В docs/коммиты — только плейсхолдеры (`<sensor SN>`, `<userId>`, `<BLE MAC>`, `<phoneId>`).
