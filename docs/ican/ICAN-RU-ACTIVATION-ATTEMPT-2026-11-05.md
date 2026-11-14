# iCan RU — попытка активации и добыча таблицы калибровки (2026-11-05)

Контекст: REDMI rosemary (`4HU4QKA6FMFIUC89`, root), приложение
`com.sinocare.ican.health.ru` (01.16.01.02), JNG debug 1.2.8 + LSPosed-хук v5.
Цель: понять, что записывается в сенсор при активации (таблица `correctCode`/`parameterCode`)
и почему калибровки не записываются.

## Итог коротко

0. **«Авария облака» оказалась временным блоком.** Через ~1.5 ч `rus.icancgm.com` и
   `iot.ru.sinocareintl.com` снова отвечают 200 (в т.ч. по хосту с `curl -4`);
   AAAA-записей нет. В момент недоступности TLS рвался на уровне IP и с домашней
   сети, и с мобильного интернета — похоже на WAF/rate-limit после серии ночных
   логинов, а не на падение RU-сегмента.
1. **Таблица калибровки для `<sensor SN>` добыта** (см. «Полученная таблица»).
   Активация не прошла из-за недоступности RU-облака в этот момент.

2. Приложение при «активации» **не отправило ни одного запроса**: лог процесса
   обрывается на license-RSA + `time-service` GET; серверных `user/active`,
   `user-apparatus/save` нет. Ошибка выдана локально/по таймауту.
3. Таблица калибровки приходит **только из RU-облака** (`cgm-code-encrypt`);
   без неё драйвер JNG намеренно не пишет дефолтную таблицу
   (`buildCorrectCodeChunks` возвращает null для generic-таблицы) — поэтому в
   сенсор ничего не записывается.
4. Единственный SN-зависимый коэффициент, выводимый офлайн: **x13** =
   `decimal(serial[9..10]) / 100` (`ICanHealthProvisioningPolicy.factoryCode`).
   Для тестового сенсора `<sensor SN>` (13 символов) это `0.64`.

## Протокол таблицы (реализован в драйвере, пригоден и с PC)

```
GET https://iot.ru.sinocareintl.com/api/sino-deviceaccess/sdk/cgm-code-encrypt
    ?appId=610001&deviceSn=<13-символьный SN>&timestamp=<ms>&sign=<UPPER(MD5(signString))>
signString = "appId=<appId>&deviceSn=<sn>&timestamp=<ms>&key=<appSecret>"
```

- Ответ: `{"code":200,"data":{"k":"<RSA-1024 ct>","v":"<AES-256-CBC ct>"}}`.
- `k`: RSA-public-op встроенным ключом SDK (`RSAUtils`, 1024-бит) → 32-символьный AES-ключ.
- `v`: AES-256-CBC, IV = key[0:16] (ASCII), NoPadding; плейнтекст может быть gzip.
- Внутри: `{"code":200,"data":{"code":"{\"x1\":...,\"x25\":...}"}}`.
- Инструмент: `tools/ican-capture/fetch_cgm_code.py <SN> --out <dir>` (креды из
  `~/.config/ican-cloud-creds.json`, не коммитятся).
- Ручной фолбэк для аппа: положить `ican-cgm-code-<SN>.json` в `filesDir` JNG.

Известная дефолтная таблица (ответ сервера на неизвестный SN; в provisioning
не используется): x1=0.015, x2=32.0, x3=25.0, x4=1.0, x5=0.8, x6=0.0, x7=40.0,
x8=0.3, x9=1.0, x10=1.2, x11=13.0, x12=1.5, x13=0.4, x14=3.5, x15=15.0,
x16=15.0, x17=0.1, x18=0.6, x19=0.2, x20=0.6, x21=0.1, x22=16.0, x23=1.0,
x24=2.1, x25=2.2.

## Полученная таблица (per-SN, ответ облака)

`x1=0.015, x2=32.0, x3=25.0, x4=1.0, x5=0.8, x6=0.0, x7=40.0, **x8=0.7**,
**x9=0.6**, **x10=1.0**, x11=13.0, x12=1.5, x13=0.4, x14=3.5, x15=15.0,
x16=15.0, x17=0.1, x18=0.6, x19=0.2, x20=0.6, x21=0.1, x22=16.0, x23=1.0,
x24=2.1, x25=2.2.`

Отличия от generic-дефолта: x8, x9, x10 — то есть `isGenericTable` его не
отвергает, драйвер запишет таблицу как настоящую. Перед записью клиент
подменяет `x13` на SN-производное (для текущего SN — 0.64).

Файл для JNG: `files/ican-cgm-code-<SN>.json` (положен в debug-сборку, читается
`ICanHealthCloudCode.cachedCode`).

## Что реально записывается в сенсор (проверено по логу активации с другого телефона)

Трасса `juggluco-trace-20261105-120553.log` (JNG 1.2.8, sensor `V01.02.01.00_N0001`,
model=iCGM-t3). Цепочка записи:

1. `F3` → `06 F3 01 8C 38 6A EA` (challenge `8C 38 6A EA`);
2. `F4 10 <16-байтный токен>` (auth);
3. `F6` → `1C F6 01 FF FF` (калибровка не прошита);
4. `CorrectCode write armed` → три 18-байтных чанка `[F2 10][AES-128-ECB]`,
   каждый подтверждён `1C F2 01`:
```
1/3  F2 10 0C 16 C4 B6 10 E4 31 87 F0 CB 3A D0 8F FF 4E C9
2/3  F2 10 2C 07 48 9E EC 36 1B 38 DD 29 A8 D2 09 B4 63 54
3/3  F2 10 77 9C AF 6A 2A DB 6C 98 27 32 33 EC 28 90 1D BF
```
5. `CorrectCode write complete: 3/3 chunks acked`.

**Верификация:** те же байты воспроизводятся офлайн из облачной таблицы
(`buildCorrectCodeChunks(table, challenge, sn)`) — совпадение побайтное, значит
таблица, добученная `fetch_cgm_code.py`, идентична той, что записала официальная
SDK-цепочка. Ранние попытки (до восстановления облака) завершались
`CorrectCode write skipped: no cached per-SN table` — сенсор оставался непрошитым.

Отдельное наблюдение (не блокер): history-RACP на этом сенсоре отвечает
`06 00 F1 0A`/`06 00 01 0A` (обе командные семьи) — история не запрашивается,
живые показания идут (пример: 23.5 ммоль/л).



## Логин: итоговый контракт (2026-11-05, blutter)

- `POST /ican/api/sino-auth/oauth/token`, `Content-Type: application/x-www-form-urlencoded`;
  заголовки: `authorization: <Basic>`, `clientid: sino-ican-android`, `country: RUS`, …
- Тело (порядок/набор как в `_$LoginRequestToJson`):
  `grant_type=phone_cgm`, `scope=all`, `type=account`, `tenantId=000000`,
  `phone_num=<account>`, `password=<argon2hex>`, прочие поля пустые.
- **Пароль хэшируется клиентом**: Argon2id, соль = байты самого пароля,
  t=3, m=32768 КиБ, p=1, version=0x13, выход 32 байта → lowercase hex
  (подтверждено векторами argon2-cffi и сервером).
- Ответ — **плоский `LoginModel` без обёртки**: `{"access_token":"<JWT>","refresh_token":"<JWT>",...}`;
  код ошибки приходит в `{"code":..,"msg":..}` (13 — неверные креды).
- JNG: `ICanHealthCloudClient.login` (форма), `ICanPasswordHash` (Argon2id),
  парсер принимает и плоский, и обёрнутый ответ.

## Логин: диагностика (2026-11-05)

- JNG-клиент шлёт `POST /ican/api/sino-auth/oauth/token` **form-urlencoded**
  (`grant_type=password&scope=all&country=RUS&phone_num=<account>&password=<pwd>`).
  Сервер разбирает форму (без `grant_type` — `code 400 "Missing grant type"`),
  но на верные креды официального аккаунта отвечает `code 13` («Неверная учётная
  запись или пароль»), хотя официальное приложение входит теми же данными.
- Гипотезы отвергнуты живыми пробами: JSON-тело — «Missing grant type»;
  конверт `{key,data}` JSON-ом — «Missing grant type»; конверт полями формы
  (`key=…&data=…`, RSA-OAEP и RSA-PKCS1) — тоже «Missing grant type».
- Перехват официального запроса не удался: MITM (системный CA через Magisk-модуль,
  прокси) — клиент приложения не доверяет CA (пиннинг/свой trust store);
  Frida-аттач к процессу блокируется (анти-tamper, attach закрывается).
- Приложение также самоубивается на старте (SIGSEGV `0x1f4/0x79c`) при наличии
  root — лечится добавлением `com.sinocare.ican.health.ru` в **Magisk DenyList**
  (после этого процесс живёт; Zygisk-инъекция хука в него, соответственно, отключена).
  Данные приложения после этого были очищены (`pm clear`), вход в официальном
  приложении повторён успешно; `loginInfo` (access/refresh JWT, expiry, поля
  LoginModel) читается из MMKV `files/mmkv/AppGlobalConfig` — хотя `shared_prefs`
  пакер ijiami шифрует, MMKV остаётся открытым.
- Дальнейший шаг: статический разбор Dart-кода логина (blutter, Dart 3.9.0,
  snapshot `97ff04a728735e6b6b098bdf983faaba`) — точные поля и возможное
  преобразование пароля.

## BLE-наблюдения (HCI, btsnoop)

- В эфире «Sinocare CGM»: `<sensor SN A>` и `<sensor SN B>`; onboarding-префикс из
  QR был `<SN prefix C>` → гейт идентичности отклонил оба, драйвер не подключался.
  Гипотеза: сенсор в этот момент **подключён к другому телефону** (BLE-периферия
  не рекламируется во время соединения), поэтому в скане только чужие сенсоры.
- Найден ложный матч имени: `ADVERTISED_SERIAL_TOKEN = [0-9A-Za-z]{16,32}`
  матчит имя `SatelliteOnline5885` целиком (19 символов) и считает его
  серийником → драйвер отказывается даже подключаться к такому устройству.
  Устройство не CGM, но эвристика ложноположительная (кандидат на фикс:
  ограничить токен hex-подобным `[0-9A-F]{13,17}` или отсекать имена-словари).
- Крах-паттерн при холодном старте RU-приложения: SIGSEGV
  `fault addr 0x1f4` и `0x79c` (исторический, воспроизводится и без хука;
  не влияет на уже запущенные процессы).

## Следующие шаги

1. Дождаться восстановления RU-облака (периодически `curl` на `time-service`).
2. Вытянуть таблицу: `fetch_cgm_code.py <SN>` либо URL+`{k,v}` с чужой сети.
3. Подкинуть `ican-cgm-code-<SN>.json` в JNG (debug) и убедиться, что драйвер
   сформировал 3×18-байтных F2-блока (см. `ICanHealthCorrectCode.encodeTable`).
4. Активация с сетевым доступом; разобрать `writeCorrectCode` в `sinocare_log`
   и `original/data/save`/`user-apparatus/save` (см. RUNBOOK).

## Гигиена

Сырые HCI/heap/дампы — только локально (`/tmp`). В репо: `<sensor SN>`,
`<userId>`, `<BLE MAC>`, `<phoneId>`; секреты вендора из кода не дублируются
в документах.
