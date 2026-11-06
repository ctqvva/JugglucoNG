# Lumiflex LinX — облако и master/follower: результаты реверса

Статус: **анализ выполнен, схема извлечена**. Код приложения распакован и лежит в
`~/Projects/Dia/lumiflex-recon/` (jadx-дерево `jadx-app/`, dex-файлы `dex/`, все строки
`strings_all.txt`).

## 1. Что анализировали

- Файл: `~/Downloads/LumiflexLinX.apk`
- Пакет: `com.microtech.aidexx.lumi.mmoll`, версия 2.4.0 (2401), флейвор `gpLumiflexMmolEdnaLaunch`
- Вендор: Microtech / pancares (AiDEX-X, LinX, Lumiflex, Equil, Pico, Smart, Vista — один кодобаз)

## 2. Защита APK и как её обошли

APK упакован商用-шеллом **MxGuard (com.mx.shell / libmxldd.so)**:

- `classes.dex` (35 МБ) содержит только 3 класса загрузчика (`MXApplication`, `CoreHelper`);
- реальный dex зашифрован/спрятан, `assets/android.msf` — метаданные;
- в `libmxldd.so` есть проверка `/proc/self/maps` (анти-Frida/Substrate). Любое
  подключение Frida (и `spawn`, и `attach`) убивает процесс мгновенно — обойти
  переименованием агента не удалось.

Рабочий способ (без Frida, без ptrace):

1. Запустить приложение на рутованном устройстве, дождаться загрузки UI.
2. Как root вычитать `/proc/<pid>/mem` по регионам `[anon:dalvik-DEX data]`
   (обязательно 64-битная арифметика смещений — 32-битная обрезает адреса).
3. В дампе найдены 4 полноценных dex (version 037); починить checksum/signature
   (Adler-32 + SHA-1) и открыть jadx.

Итог: получен весь код приложения, включая `common/net/*`, `data/Cloud*Sync`,
`ui/setting/share/*`, `ui/main/home/followers/*`.

## 3. Транспорт и аутентификация

База: `https://cloud.lumiflex.ru/` (`ApiService.Companion.instance`), ко всем путям
добавляется префикс `/api-x` (`UrlInterceptor`, бренд `lumiflex`).
Итоговый URL: `https://cloud.lumiflex.ru/api-x/<path>`.

Заголовки (`HeaderInterceptor`):

| Заголовок | Значение |
|---|---|
| `version` | `ru` |
| `x-token` | токен из MMKV (после логина) |
| `brand` | `lumi` |
| `encryption` | `enabled` (или `disable`) |
| `app-version-check` | RSA(`{"version":"2.4.0","timestamp":<ms>,"userId":<id>}`) |
| `app-info` | `com.microtech.aidexx.lumi.mmoll,2.4.0` |
| `Accept-Language` | текущая локаль |

Шифрование тела (`DataEncryptInterceptor`) — то же, что уже реализовано в
`AiDexCnCloudClient` для CN:

- POST-тело → `RSA/ECB/PKCS1Padding` → `{"encryptData":"<base64>"}`;
- ответ с `encryption: enabled` → расшифровка RSA приватным ключом;
- ключи **1024-бит**, зашиты в `RSAUtil` и совпадают с RU-приложением Microtech:
  - `PUBLIC_KEY` = `MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCifgLBAr2MhgWLBJWtTuhtGC4f47eb5xs1Ys/GvYjS77h6aX9Zw2HIpovVRLITdd0bMjD4/VHnrC1bZOD/OeXchOTmjrVlIdKVeJK4RIrYc7oDSlWUYkBKDRqWLEUXS2CS0Fc1X6wa2Pub7G9e3ukV+orrJkMC0+YTEgn18iLUBQIDAQAB`
  - `PRIVATE_KEY` начинается с `MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJc...`
- TLS: `HttpsUtil.getSslSocketFactory()` + `HostnameVerifier → true` (trust-all).

Логин/аккаунт: `/user/loginByPassword`, `/user/loginOrRegisterByVerificationCodeWithPhone`,
`/user/getUserInfo`, `/user/logout`, `/user/deleteAccount`.

## 4. Отправка данных в облако (режим «master»)

Сервисы (`ApiServiceKt`):

| Назначение | Путь |
|---|---|
| Регистрация сенсора на аккаунте | `/cgmDevice/userDeviceRegister` |
| Проверка SN по списку | `/cgmDevice/checkSnFromListBySnNumber` |
| Heartbeat сенсора | `/cgmDevice/deviceHeartBeat` |
| Статус сенсора (ошибки) | `/cgmDevice/deviceStatusUpdate` |
| Список/инфо устройств | `/cgmDevice/getUserDeviceInfo`, `/cgmDevice/getFirstAndLastDeviceInfo` |
| Загрузка истории (brief) | `/cgmRecord/saveCgmRecord` |
| Обновление записи | `/cgmRecord/updateCgmRecord` |
| Чтение истории страницами | `/cgmRecord/getCgmRecordsByPageInfo` |
| Калибровки | `/cgmCalibration/saveCalibration`, `/cgmCalibration/getCalibrationList` |
| Текущий тренд | `/userTrend/saveOrUpdateUserTrend` |
| Настройки | `/userSetting/getUserSetting`, `/userSetting/updateUserSetting` |
| Часовые пояса | `/timeChange/getTimeChangeList`, `/timeChange/save` |
| Глюкоза из глюкометра | `/bloodGlucoseRecord/saveOrUpdateFingerBloodGlucose` |

Запись CGM (`RealCgmHistoryEntity` + `BaseEventEntity`), поля JSON:

```
recordId / cgmRecordId, frontRecordId, autoIncrementColumn, idx,
userId, sensorId, deviceId, deviceSn, sensorIndex,
glucose, glucoseIsValid, quality, status, eventWarning,
rawOne, rawTwo, rawVc, rawData1..rawData9, rawIsValid,
timeOffset, deviceTime, deviceStatus, moment,
appTime ("yyyy-MM-dd HH:mm:ss"), appTimeZone, appCreateTime, dstOffset,
uploadState, briefUploadState, rawUploadState, deleteStatus, language
```

Поведение загрузчика (`data/CloudCgmHistorySync`, `CloudHistorySync`, `SyncFrequencyLimitController`):

- локальные записи помечаются `briefUploadState` (1 = к отправке brief, 2 = загружено);
- отдельно `rawUploadState` для сырых данных (raw-история идёт другим запросом);
- пагинация по `startAutoIncrementColumn` / `endAutoIncrementColumn` / `orderStrategy`;
- троттлинг: базовый интервал 30 с, при ошибках множители `{1,4,4,10,10,30,60,...}`
  (то есть backoff до десятков минут), сброс при успехе;
- `TrendInfo` (`/userTrend/saveOrUpdateUserTrend`): `trendId, trend, trendValue,
  appTime, appTimeZone, dstOffset, userId`.

## 5. Master/follower («Поделиться» / «Следить»)

Модель: **авторизация reader→provider** (`userAuthorization`). Владелец сенсора
(provider/master) выдаёт доступ по имени учёта; читатель (reader/follower) видит
данные провайдера и получает его настройки/тренд.

Пути (`ApiServiceKt`, бренд `SHARE_FOLLOW_URL`):

| Назначение | Путь |
|---|---|
| Список «кому я даю доступ» / «за кем слежу» | `/userAuthorization/findUserAuthorizationList` (параметр `type` = share/follow) |
| Добавить читателя | `/userAuthorization/saveOrUpdateUserAuthorization` |
| Изменить алиасы/уведомления | `/userAuthorization/updateAuthorizationInfo` |
| Одна авторизация | `/userAuthorization/findAuthorizationInfoById` |
| Удалить | `/userAuthorization/deleteByIds` |
| **Данные провайдера для follower** | `/cgm-record/list-recent?{params}` |

DTO:

- `ReqShareUserInfo` (создание): `readerUserName`, `readerAlias`, `hideState`,
  `normalPushState`, `emergePushState`
- `ReqModifyShareUserInfo`: `userAuthorizationId`, `providerAlias`, `readerAlias`,
  `hideState`, `normalPushState`, `emergePushState`, `extremeHighPushState`
- `ReqGetShareOrFollowUsers`: `type`
- `ShareUserInfo` (ответ): `userAuthorizationId`, `providerUserName`, `providerAlias`,
  `readerUserName`, `readerAlias`, `dataProviderId`, `dataReaderId`, `hideState`,
  `normalPushState`, `emergePushState`, `extremeHighPushState`, `isLooking`,
  `information` (UserEntity), `cgmDevice` (CgmDevice), `userSetting`,
  `userTrend` (UserTrendInfo), `autoIncrementColumn`

UI/поведение:

- `ShareFollowFragment` / `ShareFollowActivity` / `ShareAddUserActivity` (добавление по
  логину/e-mail, алиас, WeChat-вариант в CN);
- `FollowListAdapter`, `FollowSwitchActivity` — переключение между «свои данные» и
  «данные провайдера»; в `ShareFollowViewModel` `periodPullFollowList = 60000` мс,
  т.е. список/статус обновляется раз в минуту;
- follower забирает поток через `/cgm-record/list-recent` (Retrofit `@Path("params")`)
  и `getCgmRecordsByPageInfo` для истории;
- лимиты кешируются в префах: `APP_CACHE_FOLLOWER_COUNT`,
  `APP_CACHE_FOLLOWER_MAX_COUNT`, `APP_FETCH_CACHE_FOLLOWER_COUNT_TIME`.

## 6. Что уже есть в JugglucoNG и чего не хватает

Есть:

- BLE-драйвер `Common/src/main/java/tk/glucodata/drivers/aidex/` (AiDex X / LinX),
  включая алгоритм и калибровки;
- `AiDexCnCloudClient.kt` — только CN-логин и `getSnConfig` (`china.pancares.com`,
  `/backend/aidex-x/...`), т.е. получение ключей для BLE-авторизации. Загрузки данных
  в облако и share/follow там нет;
- follower-источники: Nightscout и HTTP API (не vendor-cloud).

Не хватает (для задачи):

1. `LumiflexCloudClient` (Kotlin, `HttpURLConnection`/OkHttp): база
   `https://cloud.lumiflex.ru/api-x`, те же RSA-конверты, заголовки из §3.
2. Логин + хранение `x-token` (шифрованно, как остальные секреты).
3. Master: `userDeviceRegister` (привязка SN при старте сенсора), `deviceHeartBeat`,
   `deviceStatusUpdate`, `saveCgmRecord` (батчи + retry/backoff), `saveCalibration`,
   `saveOrUpdateUserTrend`.
4. Follower: `findUserAuthorizationList(type=follow)` + `list-recent` (опрос раз в
   60 с), маппинг в существующие reading lanes; UI-переключатель «свой сенсор /
   провайдер».
5. Настройки: включение/выключение, выбор аккаунта, consent/дисклеймер.

## 7. Оценка работ

| Этап | Содержание | Оценка |
|---|---|---|
| 1 | Клиент + RSA/заголовки + логин, юнит-тесты на конверт | 1–2 дня |
| 2 | Master-загрузка (`saveCgmRecord`, регистрация, heartbeat, backoff) | 2–4 дня |
| 3 | Follower (`list-recent`, 60 с опрос, маппинг) | 2–3 дня |
| 4 | UI/настройки/статусы, документация, тесты | 2–3 дня |

Реалистично: **1.5–2 недели** на обе функции с тестами, из них follower — заметно
проще и безопаснее (только чтение).

## 8. Риски и ограничения

- **Аккаунт**: без реального логина Lumiflex ничего не проверить; сервер может
  требовать телефон/e-mail и присылать SMS/код.
- **Привязка сенсора**: `userDeviceRegister` привязывает SN к аккаунту. Если сенсор уже
  привязан к официальному приложению, возможен конфликт/отказ сервера — нужен
  эксперимент на реальном аккаунте.
- **Гео/бренд**: сервер один (`cloud.lumiflex.ru`), но брендов несколько
  (`lumiflex / linx / equilg / smart / pico / vista / mediq`); заголовок `brand`
  влияет на бэкенд-логику.
- **Не подтверждено decompiler'ом**: точные query-параметры `list-recent`
  (`{params}` собирается в inline-лямбде, jadx её не декомпилировал), ключи HashMap
  у `userDeviceRegister`, размер батча. Это лучше добить либо smali-дампом этих
  классов, либо одним живым перехватом трафика (сейчас trust-all TLS, pinning нет —
  mitmproxy подойдёт без обхода).
- **Юридически/этически**: доступ к облаку вендора возможен только с учётной записью
  пользователя; стороннему приложению стоит явно предупреждать, что данные
  уходят на сервер производителя.
- **Обновления приложения**: схема может поменяться в новой версии; нужен
  feature-flag и аккуратная деградация.

## 9. Рекомендация

1. Сначала **follower** (чтение `list-recent`) — минимум записи на сервер, быстрый
   эффект, не ломает привязку сенсора.
2. Затем **master** (загрузка), после проверки `userDeviceRegister` на живом аккаунте.
3. Перед реализацией снять один реальный прогон (mitmproxy + trust-all TLS из §3),
   чтобы зафиксировать точные параметры `list-recent` и `saveCgmRecord`.

## 10. Live validation (2026-11-02, account `<account phone>`)

Проверено на реальном аккаунте прямым HTTP-клиентом (без приложения).

- Логин: `POST /api-x/user/loginByPassword`, тело `{"userName":"<account phone>","password":"<md5 hex>"}`.
  Сервер ждёт **MD5** пароля (`EncryptUtils.md5` в официальном клиенте). Пока сессия приложения
  активна, ответ `802 "This account has been logged in from other equipments"` — **одна сессия на
  аккаунт**: вход из JNG разлогинивает официальное приложение и наоборот.
- `GET /user/getUserInfo` → `{userId, email, phone, isSettedPassword, ...}`.
- `GET /cgmDevice/getFirstAndLastDeviceInfo` → `firstList/lastList` с полями
  `deviceId, userId, sensorId, deviceModel, deviceSn, deviceMac, deviceKey, registerTime,
  unregisterTime, startUpTimeZone, sensorIndex, sensorStartUp, sensorErrorTimeOffset, et,
  deviceType, name` (name = `AiDEX X`).
- `GET /cgmRecord/getCgmRecordsByPageInfo?pageNum=1&pageSize=N` → записи с полями
  `cgmRecordId, frontRecordId, userId, sensorId, autoIncrementColumn, timeOffset (минуты),
  appTime ("yyyy-MM-dd HH:mm:ss"), appTimeZone ("Europe/Moscow"), dstOffset (строка, DST-сдвиг),
  glucose (float), status, quality, glucoseIsValid, rawOne, rawTwo, rawVc, rawIsValid,
  eventWarning, appCreateTime ("yyyy-MM-dd HH:mm:ss+0300"), trendValue, appTimeOffset, deviceStatus`.
- `GET /userAuthorization/findUserAuthorizationList?type=1|2` — `type` **числовой** (1 = share,
  2 = follow); строки `share`/`follow` дают `500 Запрос не выполнен`.
- `POST /cgmRecord/saveCgmRecord` с телом `{"records":[ ... ]}` принят (`code 200`, вернул
  `cgmRecordId` и `autoIncrementColumn`). Лишние поля (`deviceSn`, `sensorIndex`) сервер терпит.
- `sensorId` — серверный идентификатор сенсора, не серийник: берётся из
  `getFirstAndLastDeviceInfo` по `deviceSn`. `updateCgmRecord` с `deleteStatus:1` по `cgmRecordId`
  удалить запись не дал (500) — удаление осталось непроверенным.
- `userDeviceRegister` вживую не проверялся: на аккаунте сенсор уже привязан официальным
  приложением. Это ограничение той проверки, а не требование к регистрации из JNG.

## 11. Followers live check (2026-11-03)

- Фолловер, добавленный в официальном приложении, на сервере есть: `userAuthorizationId =
  <userAuthorizationId>`, `dataProviderId` = владелец аккаунта,
  `dataReaderId` = аккаунт читателя, `readerUserName = <follower phone>`.
- `findUserAuthorizationList` с `type=1` или `type=2` возвращает **пустой список** даже при
  существующей авторизации; `type=0` возвращает все авторизации аккаунта. Поэтому клиент
  запрашивает `?type=0&pageNum=1&pageSize=500` и сам фильтрует по направлению:
  share — `dataProviderId == userId`, follow — `dataReaderId == userId`.

## 12. Device registration and sensor "name" (2026-11-03)

Проверено вживую на аккаунте `<account phone>`.

### Есть ли у сенсора имя

Отдельного имени/алиаса сенсора в облаке нет: ни в одном эндпоинте `ApiService` (полный список
констант `ApiServiceKt`) нет операции переименования устройства, а в модели `CgmDevice` нет поля
имени — только `deviceModel`, `deviceType`, `deviceSn`, `deviceId`, `sensorId`. В ответе
`getFirstAndLastDeviceInfo` поле `name` вычисляется сервером из типа устройства (`deviceType=2` →
`"AiDEX X"`), клиент его не задаёт и изменить не может. Ограничений на имя нет, потому что имени нет.

### Полный payload `POST /cgmDevice/userDeviceRegister`

Сервер валидирует поля по одному и отвечает `3000xx` с сообщением; порядок проверок:

| Ответ | Поле |
|---|---|
| `300003` Укажите серийный номер устройства | `deviceSn` |
| `300007` Укажите часовой пояс активации устройства | `startUpTimeZone` |
| `300004` Укажите MAC-адрес устройства | `deviceMac` |
| `300006` Укажите время активации устройства | `sensorStartUp` |
| `300002` Укажите режим устройства | `deviceModel` (или `deviceType`) |
| `300008` Укажите ID устройства | `deviceId` |
| `300005` Укажите ключ устройства | `deviceKey` |

Принятый запрос (`200`, ответ `{"deviceId": ...}`):

```json
{"deviceSn":"0000000000","deviceMac":"00:11:22:33:44:55","startUpTimeZone":"Europe/Moscow",
 "sensorStartUp":"2026-11-03 00:00:00+0000","deviceModel":"0","deviceType":2,
 "deviceId":"0123456789abcdef0123456789abcdef","deviceKey":"<deviceKey>",
 "sensorId":"0123456789abcdef0123456789abcdea","sensorIndex":123456,"et":15,"dstOffset":"0",
 "registerTime":"2026-11-03 00:00:00+0000"}
```

- `deviceId` и `sensorId` клиент присылает сам (32 hex); сервер их сохраняет как есть.
- `userId` подставляется сервером; `deviceMac`, `deviceKey`, `sensorStartUp`, `startUpTimeZone`
  обязательны. У официального приложения MAC — это BLE-адрес сенсора
  (`deviceMac=<BLE MAC>` при `deviceSn=<sensor SN>`), `deviceKey` — base64 на 16 байт.
- Проверка SN по белому списку (`checkSnFromListBySnNumber`) на регистрацию не влияет: `0000000000`
  зарегистрировался, хотя `checkSn` для него возвращает `false`.
- `POST /cgmDevice/deviceUnRegister` с `{"deviceId": ...}` ставит `unregisterTime` (мягкое удаление);
  запись остаётся в `getFirstAndLastDeviceInfo` с заполненным `unregisterTime`.

### Что из этого следует для JNG

Реализованный в JNG поток (покрыт интеграционными тестами с ответами-заглушками,
но пока не подтверждён новой привязкой физического сенсора в этой рабочей копии)
получает обязательные поля из локального драйвера: BLE MAC, время активации и сохранённый
16-байтовый PAIR-ключ из AiDexPairKeyVault. Он регистрирует локально сопряжённый сенсор через
`userDeviceRegister`, затем повторно читает список устройств и использует подтверждённый сервером
`sensorId`. Отсутствующие MAC/ключ/время не подменяются заглушками. Clone-сенсоры исключены.
Клиентские ID стабильны для аккаунта, серийного номера и времени активации, чтобы повтор запроса
после потери ответа не создавал новую идентичность.

## 13. Проверка регистрации RU (2026-11-06)

Повторный запрос с российским номером подтвердил причину ошибки регистрации:
`/user/sendRegisterPhoneVerificationCode` с полным номером `7…` отвечает HTTP 200,
но бизнес-кодом `200100` («Отправка кода не удалась»). Это не успешный запрос.

В APK 2.4.0 присутствуют `/sms/sendRegisterOverseaPhoneVerificationCode` и модель
`ReqPhoneVerCode(zoneNumber=…)`. Запрос на этот маршрут с отдельным `zoneNumber: "7"`
и десятизначным `phone` получил бизнес-код `200` («Запрос выполнен успешно»).
Номер, SMS-код и токены в отчёт не записывались. Завершение проверки телефона и
переход к настройке пароля наблюдались в установленной debug-сборке на Pixel;
затем приложение перешло на экран подписчиков.

Для проверки кода по-прежнему используется
`/user/loginOrRegisterByVerificationCodeWithPhone` с полным номером `7…` и `code`.
Строгая проверка бизнес-кода остаётся обязательной; HTTP 200 сам по себе ничего
не говорит о доставке SMS. Поля запроса SMS и канонический номер для входа теперь
проверяются раздельно в регрессии клиента.
