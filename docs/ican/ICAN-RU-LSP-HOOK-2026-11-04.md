# iCan RU (нативное приложение) — LSPosed-подтверждение протокола, 2026-11-04

## Зачем этот документ
Результаты RE-валидации контракта `rus.icancgm.com` из [ICAN-RU-DART-RECON-2026-10-31.md](ICAN-RU-DART-RECON-2026-10-31.md)
на **живом приложении** `com.sinocare.ican.health.ru` v01.16.01.02 (rosemary, root).

Способ: Xposed-модуль (LSPosed) вместо Frida — ijiami блокирует Frida-энджин,
LSPosed-модуль работает стабильно, приложение не падает, весь трафик и крипто видно в logcat.

## Инфраструктура хука
- LSPosed: официальный v1.9.2 не работает на этом ROM (`Failed to init lsplant` → SIGSEGV в zygote-потомке);
  установлен форк **JingMatrix v1.11.0 (7209) zygisk** (`/data/adb/modules/zygisk_lsposed`).
- Приложение снято с Magisk denylist; хук-модуль включён правкой `/data/adb/lspd/config/modules_config.db`
  (таблицы `modules`, `scope`); менеджер модуль «не видит». После каждого `adb install -r`
  нужно обновить `modules.apk_path` (меняется при каждой переустановке), VACUUM, удалить `-wal`/`-shm`, перезагрузка.
- Модуль `tk.glucodata.re.icanhook` (исходники: `/tmp/opencode/ican-ce/lsp/ican-hook/`), сборка через gradle репозитория
  (AGP 9.3.2) против compile-only стабов Xposed (`api-src/`; стаб проверен на отсутствие в dex).
- Хуки v4: SQLCipher (`SupportFactory`-конструкторы, `SQLiteDatabase`), `SecretKeySpec`/`IvParameterSpec`,
  `Cipher.init/update/doFinal` (hex ≤4096, GCM отфильтрован), OkHttp `newCall`,
  `Request$Builder.build()` (фильтр `rus.icancgm.com`/`/ican/api/`), `KeyStore.getEntry`, Gson `toJson/fromJson`.
- Тег логов: `ICANX`. Логи сессии 2026-11-04: `logcat-2026-11-04-v3.log` и `logcat-2026-11-04-v4.log` (полные выборки),
  `capture-2026-11-04-v4.txt` (стрим фильтрованных тегов; локальные копии в `/tmp/opencode/ican-ce/lsp/`; в репозиторий не включаются).

## Конверт запроса (`key=`/`data=`) — LIVE-доказательство
Пример: `GET …/sino-health/v1/user-device/page?current=1&size=999` — финальный запрос идёт с `?key=…&data=…`
(logcat v3, pid 9745, 17:35:23):

1. объект запроса в Gson: `src={current=1, size=999}` → `json={"current":"1","size":"999"}` (28 байта);
2. **ручное PKCS7-дополнение до 32** — к plaintext дописано `04 04 04 04` (CIPHER-IN hex-хвост: `…393939227d04040404`);
3. `AES-256-CBC/NoPadding`, ключ = **32 случайных ASCII-символа на запрос**
   (в примере начинается `w0H6…`), **IV = первые 16 символов того же ключа** — подтверждено в `Cipher.init` (iv=keyHex[:16]);
4. CIPHER-OUT: 32 байта шифртекста → URL-encoded `data=`;
5. `RSA-2048/ECB/OAEPWithSHA-1AndMGF1Padding` шифрует **сам ASCII-ключ** (32 байта) → `key=` (256 байт).

Итог: рантайм-контур «key=…&data=…» из Dart-RECON подтверждён байт-в-байт на живом приложении.
Для POST (`app-version/upgrade/info`, BREQ json len=502) — тот же конверт, но в теле (`{"key":…,"data":…}`, форма из Dart-RECON).

## RSA-OAEP публичник (вшит в APK, не секрет)
```
30820122300d06092a864886f70d01010105000382010f003082010a02820101008a67624bbc073299850b0d23e86734ed345c8659ffa69e26f2401e0ee23c1ef45d0c1d2e97f89cf1f26e7bb3b0f184ea0843edd8829966d4df046b1f42043fa4576542b1b7d65804ad10558ad399c696f107ab763b6a47d1682e236b0260e2a839e7cf2e3893a2f32b6d641ab59ad377731e3442c3ef3635598f0d2e443952bf897bcaba5cef9c192d656e97f1090bdd2e86e17b0cbef5a457b0b6f705cb34150e11756c1e591b74823f5a913bd595a4e3806ab9fe94c4ddcadf4171718bc2fd34fbbc2ca82e62d2c3b84fdf7d849db96768c0f220264ffcc3b471022c0a0111463520e9542aacac53bda68439e9926a3b11e0d602c69c909c763daebdb429f10203010001
```
(полная строка — `CIPHER-INIT RSA/ECB/OAEP…`, logcat-2026-11-04-v3.log, 17:35:23.407; поле `keyHex`).

## Ответ сервера — расшифровка подтверждена
Расшифровка ответа — **тем же ASCII-ключом, что и запрос** (подтверждено офлайн-корреляцией,
см. [ICAN-RU-LIVE-DECRYPT-2026-11-04.md](ICAN-RU-LIVE-DECRYPT-2026-11-04.md)); клиент расшифровывает
`AES-256-CBC/NoPadding`, IV = ключ[:16]; паддинг (напр. `0b`×11) **срезается вручную на клиенте** (в CIPHER-OUT видно целиком). Пример (v4, 17:44:55.721):
```
{"code":200,"success":true,"data":null,"msg":"Операция выполнена успешно","ts":1793803495716}
```
Механика воспроизводится офлайн для малых ответов; расшифровка больших ответов
(например полный список `user-device/page`, снятый в этом сеансе только шифртекстом) — следующий шаг, материала (ключ + шифртекст) в capture достаточно.

## Авторизация и заголовки (перехват `Request$Builder.build()`)
- `Authorization: Basic <b64>` = `ican-health-android:<clientSecret>` (значение секрета уже зафиксировано в DART-RECON; в этот док не дублируем).
- UA: `okhttp/4.12.0`.
- Остальные заголовки видны в `BHDR`-строках; tenant RU — компайл-тайм константа (`rus.icancgm.com`).

## Лицензирование SDK
При старте SDK расшифровывает вшитый лицензионный блоб: `Cipher RSA/ECB/PKCS1Padding DEC` **вшитым приватным RSA-1024 ключом** (PKCS#1 hex — только в локальных capture-логах, в доках не публикуем). Расшифрованное содержимое:
```
{ "packageName":"com.sinocare.ican.health.ru",
  "sign":"60A53F5D9F265597F2AC0C3EA3722907DC8CFB08",  // SHA-1 от имени пакета
  "flag":16 }
```
Лицензия срабатывает штатно; для JNG этот механизм не нужен.

## Константные ключи IoT-слоя (вшитые, маскированные префиксами)
Каждая сессия приложения делает одни и те же операции:
- `AES/ECB/NoPadding ENC` ×4 коротких блока — ключ `97da74ee73…` (выход блока №4 совпадает с IV следующей CTR-операции — цепочка конструируется из выходов);
- `AES/CTR/NoPadding ENC` 10-байтного поля — ключ `7cb7c1af98…`;
- `AES/GCM/NoPadding DEC` раз в старт сессии — ключ `ae3c5cee7a…` (вероятная роль: расшифровка ответа `cgm-log-filter-conf-encrypt`);
- AES/GCM без keyHex → ключи в Android Keystore, не извлекаются — локальное хранилище (Tink/WorkManager-слой), для протокола `rus.icancgm.com` роли не играет.

Полные значения — в capture-логах; при необходимости можно добавить в JNG-реализацию константами.

## SDK-лог батчи (`cgm-log-filter-conf-encrypt`)
Доказано отдельным офлайн-взломом:
- `AES-128-CBC`, ключ — вшитая ASCII-константа вида `<sdkLogKey>` (см. capture-лог, приватность — тот же класс констант, что и выше; в доках маскируем, в JNG-коде будет константами);
- IV = **фиксированный**, = первые 16 символов локального `<phoneId>` (не персональный секрет, но персональный ID → плейсхолдер);
- PKCS5; plaintext начинается с `YYYY-MM-DD HH:MM:SS SourceFile (…` (логи уровня SDK: `cgm_start_scan`, `cgm_scan_result`, `cgm_bluetooth_state_change`, `cgm_bluetooth_connect_info`, `cgmSdkCoreVersion:01.02.02.03`, `cgmSdkLogVersion:1.2.2.1`).
Эти батчи расшифровываются офлайн; в RUNBOOK учесть при их приёме (нужны только для отладки SDK, в JNG не требуются).

## SQLCipher (`cgm_sdk.db`) — статус
- Конструкторы `SupportFactory`/`SQLiteDatabase` захучены (`SQLCIPHER` тег, ждёт срабатывания);
  во всех наблюдаемых сессиях до активации сенсора БД не открывается — открытие произойдёт при активации нового сенсора (план: завтра, см. RUNBOOK).
- Оффлайн-брутфорс не удался: пароль вычищается из памяти сразу после открытия (`clearPassphrase`),
  простые кандидаты не подошли — пароль должен быть пойман хуком в момент `SupportFactory`.

## Конечные точки, виденные live (2026-11-04)
- `POST/GET https://rus.icancgm.com/ican/api/sino-cgm/v1/user/get-user-info` (конверт)
- `GET …/sino-health/v1/user-device/page?current=1&size=999` (конверт; большой ответ пока снят только шифртекстом)
- `POST …/sino-cgm/v1/app-version/upgrade/info` (json-тело 502 байт — конверт в теле)
- `GET …/sino-cgm/v1/country/setting/detail/device?country=RUS&device=i3`
- `GET …/sino-medical/v1/invite/unread-count`
- вне контура: `iot.sinocareintl.com/api/sino-system/inner/time-service?t1=<эпоха мс>` (plain), `iot.ru.sinocareintl.com/api/sino-deviceaccess/sdk/cgm-log-filter-conf-encrypt` (plain GET + шифрованные батчи).

## Что осталось
1. Расшифровка живых данных готова: [ICAN-RU-LIVE-DECRYPT-2026-11-04.md](ICAN-RU-LIVE-DECRYPT-2026-11-04.md);
   остаток: полный `user/get-user-info` (9728 Б, усечён хуком — нужен dump >4KB в файл на устройстве),
   ответы `country/setting/detail`, `invite/unread-count`;
2. активация нового сенсора с живым модулем: `SQLCIPHER` (пароль `cgm_sdk.db`), калибровка (`correctCode`, блоки F2 3×18),
   выгрузка (`original/data/save`, `detection-data/add`, `user-apparatus/save`), heartbeat;
3. прямая загрузка IoT-слоя (`iot.ru.sinocareintl.com`) — проверить на активации, не идёт ли туда glucose-выгрузка, и нужен ли второй контур для JNG;
4. коммит-гоу для этой и прочих RE-документов — после ручного апрува.

## Findings 2026-11-04 (вечер): логин невидим для Java-хуков
Цель «поймать logout→login» за вечер: 8 попыток логина пользователем под v5 и v6-хуками —
`POST sino-auth/oauth/token` **ни разу не появился** в ICANX-логах, но вход реально проходил
(токен менялся, приложения возвращались к main-screen).

Вывод: логин проходит по **Dart-слою** (`SinoAuthService::login` → `CryptoUtil::createEncryptedRequest`
→ dart:io/pointycastle), полностью внутри snapshot'а Flutter — мимо okhttp/Cipher, которые хукает
модуль. Всё, что ловили до сих пор (user-device/page, country/setting, get-user-info, BHDR с Sino-Auth) —
это native CGM SDK (libdatajar.so), получающий токен через Pigeon `_sendTokenToNative`.

Дополнительные факты:
- v5-ке capture-лог `/tmp/ican-lsp-live-v5.txt` содержит **полный JWT из заголовка sino-auth** (BHDR-чанк)
  — payload JWT == набор полей LoginModel (tenant_id, account_id, user_id, email `<email>`, phone `<phone>`,
  exp, jti, app_id, mch_id, client_id, scope, centre, language_key, status, supportBgm…). Это «ответ» логина
  в статическом виде; можно использовать как контроль расшифровки LoginModel в JNG.
- native-обертка POST (app-version/upgrade/info): `Content-Type: application/json`, тело = `{key:…, data:…}`
  (конверт ключ-обертка+AES-CBC-RND). Значитnative-POST идёт через json-конверт, а не form-urlencoded.
- вообще все «наши» successful-request'ы — native SDK; Dart-слой endpoints (sino-ican/*, dispatcher, sino-auth/*)
  в Java-хуки не попадают ни разу за день — прямое подтверждение.
- Для wire-формата SinoAuthService::login (form vs JSON-envelope) static-данных достаточно для
  гипотезы-реализации (rayon: тот же `createEncryptedRequest` конверт json-в теле, что у native-POST),
  проверка — эмпирически из JNG-клиента или Frida-хук Dart (blutter_frida.js).
- adb-линк ненадёжен: `adb reboot` по серийнику молча фейлится («device not found» — попробовать повторно),
  верный рев surrogate: `adb shell "su -c reboot"`, после — ждать boot_completed и **проверить
  `/proc/uptime`** (молча не-ребуты маскируются под успешные).
- ring-буфер logcat main на rosemary крошечный (128 KB) и перезатирается ~30–60 с под шум; heavy-тpафик
  теряется, если не писать непрерывно. Рабочая схема семантики: под `nohup`-writer на устройстве
  (`logcat -s ICANX -v time >> /data/local/tmp/icanx.log 2>&1 &`) — живёт независимо от adb-link,
  затем `exec-out su -c cat` для выгрузки. Схема задачalis успешно (393→1585 строк в течение вечера).
- HTTP-структура буфера периодически «штормит» Бар epic масштабов (~30 000 строк за ~30 с) от прочих
  процессов — на фоне ICANX-стучащие строки теряются, поэтому ICANX-логи должны писаться per-tag
  (`-s ICANX`) — так и удалось.
- хронологический «SIGSEGV fault addr 0x79c» по random pid'ам приложения (pc=0x79c, sp=0) —
  система предмет сервиса/auxiliary-процессов приложения, падение НЕ связано с хуком (prodatки
  вкладки tombstones с 17:44 и дальше, под v3/v4/v5 равномерно). Приложение работает в основном
  процессе спокойно; не влияет на захват.
- super-workv5 hook format: `BHDR <имя>: #N <value>` и `CIPHER-… hex=#N <chunk>` — браузер парсится
  `tools/ican-capture/lsp_decrypt.py` (склейка по `#N`-меткам, дедуп по (pid,tid,kind,алгос,hex-src)).
- ~01:00 МСК сервер `rus.icancgm.com` начал рвать TLS (`SSL_ERROR_SYSCALL`/connection reset) с домашнего IP
  (google/iot при этом 200; с Mac — то же самое) — временный WAF/IP-блок после серии попыток логина;
  проверка JNG-логина отложена до снятия блока (утро). Это транспортный отказ, не отказ по конверту.

## Правила публикации
Личные данные (эл. почта, `<userId>`, `<phoneId>`, `<sensor SN>`, BLE MAC, серийник устройства) в репо не попадают;
секрет-константы приложения (Basic-auth секрет, приватные ключи, симметричные ключи) в этой доке маскированы —
значения есть в локальных capture-логах и частично в DART-RECON (его строки с секретами тоже под решением при коммите).
