# План проверки iCan Health на родном RU-приложении

Дата: 2026-11-02. Устройство: Redmi rosemary (`<device serial>`), root (Magisk), RU-апп `com.sinocare.ican.health.ru` залогинен, сенсор i3 привязан.
Цель: закрыть открытые вопросы по работе вендорского приложения — активация, родная калибровка, userId, облачная активация, выгрузка глюкозы, heartbeat.

## 0. Подготовка
- Включить HCI snooping (Настройки → Для разработчиков → «Включить отслеживание Bluetooth HCI»).
- Снять baseline: `AppMMKV`, `AppGlobalConfig`, `OkHttpCache`, `shared_prefs`, дамп памяти (`dump_anon.sh`).
- Сделать tar-бэкап `/data/data/com.sinocare.ican.health.ru/` (root) для повторяемости.
- `adb logcat -v time > logcat.txt` на всё время.

## 1. Активация родным приложением (BLE + cloud)
1. Включить HCI snoop, обнулить лог.
2. В приложении активировать новый сенсор (или переактивировать текущий).
3. Снять: `/data/misc/bluetooth/logs/btsnoop_hci.log`, дамп памяти (до/после), OkHttp-кэш.
4. Разобрать HCI: `parse_att.py` + `export_transcript.py` (auth редактируется).
5. Зафиксировать: `F3` challenge → `F4` token → `F6` sensor info (`launcherState`, marker) → `0x1A` start session; корректный `authUserId`.
6. В облаке: URL с `postparam`/`?key=&data=` — `user-apparatus/save`, `original/bind`, `original/code`, `original/default/parameter`, `user-apparatus/update` (окно детекции).

Evidence: btsnoop + transcript + дамп с URL.

## 2. Родная калибровка (correctCode)
1. Выполнить калибровку в приложении (или активацию с таблицей).
2. Снять HCI + дамп памяти.
3. Искать: `F2`-чанки (18 байт), `F6` marker 500, `writeCorrectCode`, `parameterCode`, `encryptedKey`/`decryptAesKey`/`encryptedValue`.
4. Сравнить блоки с CE-энкодером (`bean.a.a(json, sn, false)` → `g.a(list, F3, versionFlag, false)`); проверить `versionFlag`, список 15/25 float, `x13` из короткого SN.
5. SDK-логи (`BleFileManager`): если есть — расшифровать (AES-128-CBC, key из prefs `"test"`, IV=androidID), вытащить таблицу/список/ключ.

Evidence: 3×18-байтных блока, совпадающие с энкодером; `parameterCode`; `decryptAesKey`.

## 3. userId / чужой аккаунт
1. Взять сенсор, активированный другим аккаунтом.
2. Активировать в RU-приложении, снять HCI/дамп.
3. Зафиксировать `1C F4 02 <payload>` → декодирование userId → повторный `F4`; правило деривации `authUserId` из serial (`P1TH…`/`ZP1TH…`).

## 4. Выгрузка глюкозы (OI3) и heartbeat (OI4)
1. Дать приложению поработать 15–30 мин с активным сенсором.
2. Снять OkHttp-кэш + дамп памяти + HCI.
3. Зафиксировать payload `user-apparatus/save` / `original/data/save` / `detection-data/batch-add` (структура видна в Retrofit invocation/дампе).
4. Зафиксировать heartbeat: URL, каденс, тело (`sino-intl/.../heartbeat`, `user-device/update-connection-time`).

## 5. IV и response key (OI1/OI2)
1. Live-проба нашим клиентом: логин (plaintext) → зашифрованный POST с IV=`key[:16]`, затем `key[16:32]`, нули, `data[:16]`.
2. Если сервер отвечает расшифровываемым телом — IV подтверждён; иначе искать `aesKey` в ответе.
3. Кросс-проверка: реальный зашифрованный запрос из кэша/дампа + ключ из памяти.

## 6. Lifecycle / аккаунт
1. Logout → `user/logout` vs `user/logoff-new`.
2. Unbind → `user/unbind`, `user-device/batch-unbind`.
3. Rebind → `rebind/save`, `rebind/save-account`.
4. Активация аккаунта (код 30) — как показывается.

## Deliverables
- `btsnoop_hci.log` + разобранный transcript (redacted).
- Дампы памяти (redacted) с URL/`postparam`/`parameterCode`.
- OkHttp-кэш (URL + размеры).
- SDK-логи (если есть) и расшифрованные таблицы.
- Заметка `docs/ican/ICAN-RU-NATIVE-VERIFY-<дата>.md` с выводами по каждому пункту.

## Ограничения
- Frida на rosemary блокируется ijiami — только HCI snoop + дамп памяти + SDK-логи.
- Секреты/серийники/токены в заметки не попадают; только redacted.
- Для активации нужен новый сенсор (или переактивация текущего) — расходует сенсор.
