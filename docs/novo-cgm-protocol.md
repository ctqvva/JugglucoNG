# DN- / CGMProtocolNOVO — спекулятивный драйвер

Статус: **экспериментальный драйвер, подключённый к приложению**. Код лежит в
`Common/src/main/java/tk/glucodata/drivers/novo/` и тестах
`Common/src/test/java/tk/glucodata/drivers/novo/*Tests.kt`. Драйвер зарегистрирован
как семейство `NOVO(11)` с собственным setup-wizard; протокол проверен только статически.

## Почему это спекулятивно

Источник — декомпиляция `apks/bioecare/base.apk` (jadx 1.5.5, apktool 2.12.1),
исходники в `/tmp/bioecare_cgm_jadx` и `/tmp/bioecare_cgm_apktool`. По коду APK:

- `CGMBluetoothScanner.BLE_DEVICE_NAMES = {SCGM, BECGM}` — префикс `DN-` **не сканируется**.
- `CGMProtocolService` выбирает протокол только для `SCGM` и `BECGM`; `CGMProtocolNOVO`
  нигде не инстанцируется (`new CGMProtocolNOVO` отсутствует).
- `EncryptUtils.deviceName2hexadecimalUpper()` нигде не вызывается — преобразование имени
  в `deviceCode` не подключено.
- Парсеры `msgReader`/`msgReaderNotify` сравнивают `byte b2 = data[2]` с 16-битными
  константами (`10919 == 0x2AA7`, `10920 == 0x2AA8`, `7169 == 0x1C01`, …). Байт не может
  равняться числу >255, поэтому **все ветки недостижимы**. Смещения полей взяты из тех
  недостижимых веток и являются приблизительными.
- Подписки на уведомления в `CGMProtocolNOVO` нет: `notifyMsg`/`indicate` не вызываются.
- ADC-слово в кадре измерения не разбирается нигде; парсер утерян.

 Поэтому драйвер повторяет протокольные константы и форматы, но **не публикует глюкозу**:
нельзя получить ADC из measurement-кадра. Функция `NovoParser.glucoseMmol`
реализована (формула проверена по `LinearRegression`), но вызывать её неоткуда.

## Расхождения между описанием и байткодом

Зафиксировано при проверке, чтобы не потерять:

1. **Ключ AES.** В smali `EncryptUtils` массив ключа:
   `2B 7E 15 16 28 AE D2 A6 AB F7 15 88 09 CF AC EF`.
   В brief было `…09 CF 4C EF` — байт 14 равен `0xAC`, а не `0x4C`.
   Подтверждено тремя векторами: базовый ключ с `0xAC` даёт
   `aef53869b88ed2df40b1b1cff385c4a5`, `2a5e7c2a52c27fdd9a34e1e5c74c8993` и
   `1227d97b38bd6672f07a1a315c560f80` для трёх открытых текстов.
2. **Второй вектор.** Значение `aeb3a22cfa2fd17529eb9cab86680be9` воспроизводится
   ключом `…09 CF AC 31`, то есть заменён **последний** байт на `0x31`, а не
   `key[14]=0x31, key[15]=0x30`, как было в brief. Порядок записи в ключ в байткоде:
   `key[14] = passwordHigh`, `key[15] = passwordLow` — код драйвера следует ему.
   В тесте вектор зафиксирован как `encrypt(payload, lo=0x31, hi=0xAC)`.
3. **Целевая характеристика команд.** `getNOVOEnumByCmd()` всегда возвращает
   `CHARACTERISTIC_APPEARANCE` (`0x2A01`) — заглушка. В `NOVOBleConnectHelper`
   `CHARACTERISTIC_CGM_SPECIFIC_OPS_CONTROL` соответствует `0x2AAC`, а `0x2B2A` —
   `CHARACTERISTIC_DATABASE_HASH`. Драйвер пишет в `0x2AAC`, как в стандарте CGM;
   утверждение brief про `0x2B2A` сохранено здесь как расхождение, требующее захвата.

## GATT

Сервисы: `1800`, `1801`, `180A`, `180F`, `181F`.
Характеристики (полные UUID с Bluetooth base UUID):

| UUID | Назначение в драйвере |
| --- | --- |
| `2A00`..`2A01`, `2A05` | GAP / Appearance / Service Changed |
| `2A19` | Battery Level |
| `2A24`..`2A29` | DIS (model, serial, fw, hw, sw, manufacturer) |
| `2A52` | Record Access Control Point |
| `2AA7` | CGM Measurement — уведомления |
| `2AA8` | CGM Feature — чтение/разбор device info |
| `2AA9` | CGM Status — ответ на inquiry |
| `2AAA`..`2AAB` | Session Start/Run Time |
| `2AAC` | CGM Specific Ops Control Point — запись команд |
| `2B29`, `2B2A` | Client Supported Features / Database Hash (не используются) |

CCCD — стандартный `00002902-…`. В коде NOVO CCCD не трогается; сопряжения нет.
Драйвер подписывается на `2AA7` через стандартный CCCD.

## Криптография

AES-128 ECB, базовый ключ — протокольная константа клиента, **не пользовательский секрет**.
Перед шифрованием payload вручную дополняется до 16 байт байтом `padLen = 16 - len`;
шифруется первый блок 16 байт (`Cipher.doFinal` расширяет до 32 байт, драйвер берёт первые 16).
`decrypt()` всегда базовым ключом и PKCS5: ручной padding снимается как обычный PKCS#5.
При `useDefaultPassword = false` меняются `key[14] = passwordHigh`, `key[15] = passwordLow`.
Соль setup-пароля — ASCII `ABC1234`. Векторы зафиксированы в `NovoCryptoTests`.

CRC в командах передаётся `*Low, *High` (LE u16). Алгоритм в декомпиляции не найден:
`crcLow/crcHigh` приходят аргументами. `NovoCrypto.crc16CcittPlaceholder` — **заглушка**
(CRC-16/CCITT-FALSE), используется только для воспроизводимости тестов; реальный полином
требует захвата.

## Команды (plaintext до шифрования)

| Команда | Байты |
| --- | --- |
| setup password | `7A <lo> <hi> 41 42 43 31 32 33 34` (10 B) |
| verify password | `7B 00 <lo> <hi> <crcLo> <crcHi>` (6 B) |
| startup | `1A <BE u32 unix_s> <fcLo> <fcHi> <tz> 00 01 01 00 00 <crcLo> <crcHi>` (15 B) |
| history data | `01 00 <startLE_lo> <startLE_hi> <endLE_lo> <endLE_hi> <crcLo> <crcHi>` (8 B) |
| clear history | `05` |
| flash count inquiry | `09 02 <lo> <hi>` |
| flash read | `0A 04 <offLo> <offHi> <cntLo> <cntHi>` |
| local package | `04 <hi> <lo>` (1-based BE u16) |

Очередь: одна команда каждые 100 мс, каждая 10-я — с паузой 1 с (в байткоде это
`Thread.sleep(100)` + `sleep(1000)` на каждом 10-м шаге; точные задержки требуют захвата).
Драйвер не очищает историю при подключении.

## Ответы

Коды: `2A24`..`2A29`, `2AA7`, `2AA8`, `2AA9`, `2AAA`, `2AAC`, `1C01`, `1C1A`, `1C7A`,
`1C7B`, `1D00`, `2B29`, `2B2A`, `AA00`, `BB00`. Драйвер диспетчеризует по UUID
характеристики, а не по байту opcode — это обходит описанный выше баг вендора.
Смещения полей (приблизительные, из недостижимых веток):

- `2AA7` measurement (15 B): `[0] len`, `[1..2] offset LE`, `[3..4] reserved1`,
  `[5] isHistory`, `[6] trend`, `[7..8] electricity LE`, `[9] reserved2`,
  `[10] reserved3`, `[11] status`, `[12] cv/100`, `[13..14] CRC LE`. **ADC нет**.
- `2AA8` device info (11 B): `[0] origin`, `[1..3] version`, `[4] passwordExist`,
  `[5] crcSupported`, `[6] factoryCalibrationSupported`, `[7] workTime`,
  `[8] sampleInterval min`, `[9..10] CRC`.
- `2AA9` flash inquiry (7 B): `[0..1] count LE`, `[2] status`, `[3..4] factoryCode LE`,
  `[5..6] CRC`.
- `2AAA` session time (11 B): `[0..3] startTime LE u32`, `[4..7] runTime LE u32`,
  `[8] timeZone`, `[9..10] CRC`.
- ACK `1C01/1C1A/1C7A/1C7B/2AAC` (5 B): `[0] flag`, `[1] tmpOpCode`, `[2] result`,
  `[3..4] CRC`.

Статусы: `0` норма, `8` истёк, `51` поляризация, `36/37/38` stop по току/CV,
`65/66/67` аномалии. Время истории: `historyEndTimeStamp = now` в начале чтения,
`timestamp(index) = end - index * 60000`, пакеты по 100.

## Глюкоза

`ta_x100 = round2((adc * 1.8 / 4096 - 0.34) * 1000 / 30 * 100)`;
`mmol = round2(ta_x100 / 100 * k - b)`, `k = convNum`, `b = verifyVal` (сервер/вручную).
Fallback `k = 0.03989725`, `b = -33.257442` стоит в вендоре, но **не пригоден для чужих
датчиков**; в драйвере рабочие значения по умолчанию `0.0/0.0` (не пригодны), чтобы
случайно не принять чужую калибровку. Батарея ≤1900 мВ — low power. Сглаживаний BIOE нет.

## Что реализовано

- `NovoConstants` — префикс `DN-`, UUID, opcode'ы, тайминги, константы формулы.
- `NovoCrypto` — ключ/паддинг/шифрование, `deviceCode` из имени, CRC-заглушка.
- `NovoParser` — сборка команд, разбор пяти форматов ответов, формула глюкозы.
- `NovoBleManager` — подключение, discovery, подписка на `2AA7`, очередь команд с паузами,
  диспетчеризация по UUID, запрос истории.
- `NovoRegistry` / `NovoIdentityAdapter` / `NovoDriver` — сохранение датчика,
  распознавание `NOVO-…`, адаптер идентичности по образцу `bioe`.
- Тесты: 14 тестов (`NovoCryptoTests`, `NovoParserTests`), включая все AES-векторы.

**Не реализовано намеренно:** публикация глюкозы в history/current (нет ADC-слова),
автоматическая запись setup/verify/startup-пароля, проверка CRC, реальный алгоритм CRC.

## Выполненные правки в общих файлах

Все пункты ниже применены: `NOVO(11)`, `NovoConstants.UI_FAMILY`, реестр идентичности,
`SensorVendor`, `SensorSourceResolver` (`SENSOR_KIND_NOVO`), `SensorTypePicker`, оба экрана,
строки и `NovoSetupWizard.kt`. Фильтр сканирования `DN-` реализован в `NovoBleManager`.

1. `Common/src/main/java/tk/glucodata/drivers/ManagedBluetoothSensorDriver.kt` — добавить
   `NOVO(11)` в `enum class ManagedSensorUiFamily` (не менять существующие `nativeCode`).
2. `NovoConstants.UI_FAMILY` — заменить временное `ManagedSensorUiFamily.BIOE` на
   `ManagedSensorUiFamily.NOVO` (единственная точка замены в новом коде).
3. `Common/src/main/java/tk/glucodata/drivers/ManagedSensorIdentityRegistry.kt` — импорт
   `NovoIdentityAdapter` и добавление его в `ManagedSensorIdentityRegistry.all`.
4. `Common/src/main/java/tk/glucodata/SensorVendor.kt` — `ManagedSensorUiFamily.NOVO` в
   `fromManagedFamily` → `BIOECARE` и в `SensorTypeName.fromManagedFamily` → новая запись
   (например `NOVO_CGM` с новой строкой).
5. `Common/src/main/java/tk/glucodata/SensorSourceResolver.java` — новый `SENSOR_KIND_NOVO`
   и ветки `family → kind`, `kind → "Bioecare"`.
6. `Common/src/mobile/java/tk/glucodata/ui/components/SensorTypePicker.kt` — `SensorType.NOVO`
   и `SensorTypeOption(..., R.string.novo_sensor, R.string.novo_sensor_desc)`.
7. `Common/src/mobile/java/tk/glucodata/ui/DashboardScreen.kt` и `SensorScreen.kt` —
   `showNovoWizard` и вызов `NovoSetupWizard(...)` по образцу `BioeSetupWizard`.
8. `Common/src/main/res/values/strings.xml` — `novo_sensor`, `novo_sensor_desc`,
   `novo_setup_*` (без реальных серийников/токенов).
9. Новый `Common/src/mobile/java/tk/glucodata/ui/setup/NovoSetupWizard.kt` — по образцу
   `BioeSetupWizard`, но с полем device code `DN-…`, k/b и паролем; вызывать
   `NovoRegistry.addSensor(...)`. **Создавать только после снятия запрета на правки UI.**
10. `Common/src/main/java/tk/glucodata/SensorTypePicker.kt` при наличии поиска по имени —
    добавить префикс `DN-` в фильтр сканирования (в APK он отсутствует).

## Риски / непроверенное

- ADC → глюкоза: кадр измерения не содержит ADC; любая публикация была бы выдумкой.
- Реальный CRC: неизвестен; команды с CRC-заглушкой device может отвергнуть.
- Характеристика для записи: `0x2AAC` выбрана по стандарту, вендор writes в `0x2A01`.
- Форматы ответов: смещения из недостижимых веток; длины и порядок полей требуют HCI-захвата.
- Пароли setup/verify/startup: когда и с каким ключом шифруются — не установлено.
- Сопряжение/CCCD: вендор не подписывается; наше подключение может отличаться от реального.
- Калибровочные `k/b`: без серверных значений драйвер не выдаст результат; это ожидаемо.
- Ключ `0xAC` и вектор №2 подтверждены локально через `openssl`; исходный brief содержит
  неточности, указанные выше.
