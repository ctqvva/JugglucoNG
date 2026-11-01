# iCan Health RU (com.sinocare.ican.health.ru) — blutter-дизасм Dart-слоя

Дата: 2026-10-31. Вход: `iCan+Health_01.16.01.02_APKPure.xapk` (versionCode 30, snapshot Dart 3.9.0, hash `97ff04a728735e6b6b098bdf983faaba`, compressed pointers).
Инструменты: blutter (dartvm 3.9.0 собран), patched `extract_dart_info.py` (dynsym по `sh_type`, whole-file фолбэк для libflutter.rodata), ELF-патч dynsym (addr=0x288 size=0x78, 5 символов) на `libapp_fixed.so`.
Вывод: `T/opencode/ican-ce/blutter-ru-390/` (asm/, pp.txt, blutter_frida.js, ida_script).

## Транспорт (ApiClient::post/get, services/api_client_service.dart)

```
post<Y>(String path, Map<String,dynamic> data, parse(dynamic)->Y, {headers?, isEncode, params?})
get<Y>(String path, dynamic params, parse)
```

- URL: `_getBaseUrl(path)`.
  - 4 dispatcher-эндпоинта (`sino-dispatcher/api/v1/{center-user/get,language/list,country/list,phone-code/list}`) → `${appConfig.baseUrl}${appConfig.appHostFirstPath}${path}`
  - остальные: если `path` абсолютный URL → как есть; иначе если `appConfig.subdomain непуст` → `${subdomain}${appHostFirstPath}${path}`, else `${baseUrl}${appHostFirstPath}${path}`.
- Конфиг: `AppInitializerService::initAppConfig` — все поля приходят из натива через Pigeon `NativeNavigatorApi::getAppConfig()` (fallback `https://cgmcepre.sinocare.com`), плюс RSA-паблик из натива → `CryptoUtil.updateRsaPublicKey`. Имена полей `copyWith`: appHostFirstPath="/api/", appName="iCan Health", appVersionCode="30", authorization=Basic, clientId, countryCode, isEncrypt=true, locale, loginTypes, passwordRule, platformType="1", subdomain, themeName="red".
- `authorization` дефолт: `Basic aWNhbi1oZWFsdGgtaW9zOjFiMTdkYWQ4MzUyZTQ5ODU4YzQyMThhNjVlNDcyMzMw` = base64("ican-health-ios:1b17dad8352e49858c4218a65e472330"). Для андроида пара из assets/config.json: `ican-health-android:e91a8c9c5b4f40faa11263e7bb7ca0a7`.
- Interceptor (0x49af10) на КАЖДЫЙ запрос ставит headers:
  `Sino-Auth` = TokenStorage token (если есть); `platformType`; `langCode` (Locale.toLanguageTag); `country`; `versionCode`; `versionName`; `clientId`; `authorization` (Basic); `timeZone` (hours от DateTime.timeZoneOffset).
- 401 (`_handleUnauthorized`): только `TokenStorage::deleteToken` + `NativeRoute::logout` — тихого refresh в интерцепторе нет (`grant_type=refresh_token` есть в PP как отдельный сценарий).
- Логин-ошибка кода 30 → `showActivateAccountDialog`.

## Крипто (utils/CryptoUtil.dart)

- RSA: RSAKeyParser(строка pub) → `OAEPEncoding.withSHA1` (RSA-OAEP-SHA1); вход — UTF-8 строки; выход → `base64UrlEncodeSafe`.
- AES: `aesEncrypt(data, key)`: `jsonEncode(data)` → UTF-8 → самодельный PKCS#7 (`Pkcs7Encoder::encode`) → pointycastle `CBCBlockCipher(AESEngine())` + `ParametersWithIV(KeyParameter(keyUtf8[0:32]))`, AES-256-CBC → `base64UrlEncodeSafe`.
  IV не передаётся в теле — высчитывается/фиксированный серверно (кандидаты для пробы: key[0:16], key[16:32], константа). Задокументированный alphabet `getRandomString(32)`: `ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678`.
- `createEncryptedRequest(data, key)` → `{ "data": <AES(jsonEncode(data))>, "key": <RSA-OAEP-SHA1(key)> }` — это тот самый runtime-контур «key=…&data=…» из CE-конвоя.
- `isEncode=true` в AppConfig — использовать шифрованный запрос при post.

## Аутентификация

`SinoAuthService::login` → POST `sino-auth/oauth/token`
- headers `{ "authorization": appConfig.authorization }` (Basic)
- тело = `_$LoginRequestToJson`:
  `grant_type` (`password` / `verify_code` / `refresh_token`), `scope`, `type`, `thirdAuth`, `country`, `ids`, `tenantId`, `phone_num`, `password`, `thirdAuthKey`
- ответ → `LoginModel`: oauth-поля `access_token`,`token_type`,`refresh_token`,`expires_in`,`scope`,`tenant_id`,`tenant_ids`,`jti`,`country` + userinfo `account`,`account_id`,`account_type`,`app_id`,`avatar_url`,`client_id`,`client_type`,`email`,`host`,`im_account`,`language_key`,`mch_id`,`mch_code`,`nick_name`,`oauth_id`,`org_id`,`phone`,`phoneAreaCode`,`post_id`,`real_name`,`role_id`,`role_name`,`sign_account`,`state_mode`,`status`,`supportBgm`,`centre`,`centerCode`,`user_id`.
- Токен → `TokenStorage::saveToken` + `_sendTokenToNative` (Pigeon).

Ссылки на код реальны: `SinoAuthService` 2 метода; `LoginService::login` (pages/login/service/login_service.dart).

## SinoIcanService (apis/sino-ican.dart) — 15 методов

| Метод | HTTP | Путь | Вызывается из |
|---|---|---|---|
| getAuthBasicInfo | GET | `sino-ican/v1/basic/config/detail` | sharing_manage, send_invite |
| updateAuthBasic | POST | `sino-ican/v1/basic/config/follower/update` | sharing_manage `_updateAuthBasicInfo` (keys: `shareFollower`) |
| hasFollowerFriends | GET | `sino-ican/v1/follower/has-follower` | sharing_welcome |
| getFriendsInfo | POST | `sino-ican/v1/follower/get-follower` | friend_setting (`id`) |
| followerFriendsList | GET | `sino-ican/v1/follower/list` | sharing_manage `_friendsList` |
| followerFriendsSave | POST | `sino-ican/v1/follower/save` | send_invite/sharing_manage `_sendInvite` (keys: `email`,`phone`,`phoneAreaCode`,`name`,`id`,`invitedTime`) |
| updateFriendsInfo | POST | `sino-ican/v1/follower/update` | sharing_manage `_deleteInvite` (`status`), friend_setting `_editFriendNickname` |
| register | POST | `sino-ican/v1/user/register` | register pages |
| sendVerifyCode | POST | `sino-ican/v1/user/verify-code/send` | register/forgot |
| checkVerifyCode | POST | `sino-ican/v1/user/verify-code/verify` | register/forgot |
| resetPwd | POST | `sino-ican/v1/user/reset-pwd-new` | forgot |
| rebindsSave | POST | `sino-ican/v1/user/rebind/save` | update_account |
| rebindsSaveAccount | POST | `sino-ican/v1/user/rebind/save-account` | update_account |
| unbindAccount | POST | `sino-ican/v1/user/unbind` | update_account |
| getDeviceSupportList | GET | `sino-ican/v1/phone-version/list` | device support |

FollowerFriendsModel JSON-ключи: `id`,`userId`,`followerId`,`sharerId`,`sharerName`,`phone`,`email`,`phoneAreaCode`,`countryCode`,`followerStatus`,`followerStatusName`,`invited`,`invitedTime`,`createTime`,`updateTime`,`deleteTime`,`status`,`isDeleted`,`unfollow`,`viewPermission`,`shareFollower`,`shareHcp`,`dateType`,`timeFormat`,`unit`,`highGlucose`,`lowGlucose`,`highMessage`,`lowMessage`,`highSounds`,`lowSounds`,`hiVibration`,`loVibration`,`vibrateOnly`,`sysSound`,`deviceDisconnectSound`,`notDisturb`,`overrideDisturb`,`silenceAll`,`silenceAllBeginTime`,`silenceAllEndTime`,`urgentReminder`,`createAccount`,`createOrg`,`centerCode`,`centre`,`techSupport`,`cloudSync`,`appPermissionSwitch`,`analyzeDataChn`,`analyzeDataEur`,`improveProdEur`.

## SinoCgmService (apis/sino-cgm.dart)

- POST `sino-cgm/v1/user/get-user-without-login`
- GET  `sino-cgm/v1/country/setting/detail` (тут, не в dispatcher)
- POST `sino-cgm/v1/user/active`

`common/upload`, `user-apparatus`, `sino-health`, `sino-intl`, `v2` в Dart-слое отсутствуют → upload-путь живёт в нативе (CGM SDK), зашифрованном ijiami: `libdatajar.so` (18.7МБ) + `libDexHelper.so`; classes.dex 80КБ = шелл. Подтверждённые нативные activity: `com.sinocare.cgm.us.*`, `com.sinocare.cgm.cgm_sdk.util.SdkContentProvider`, манифест-meta `SINOCARE_APPKEY`, `SINOCARE_CGM_APP_KEY`.

## Продуктовый контекст (i18n ru.json, 8 namespaces)

- Фича = **iCan ACCESS**: пациент приглашает «партнёров по уходу» **кодом приглашения по SMS/email**, partner вводит код в приложении **iCan Reach** после регистрации/логина. Push-approve нет.
- Паролей-правил: `passwordRule 1,2,3,4`; login types: email/phone/google/apple (iCan Health RU广告 config.json: `email,phone,google`).

## Открытые вопросы (след. этапы)

1. IV для AES-CBC (кандидаты probe: key[0:16], key[16:32], фиксированная константа) — проверить живым сервером.
2. Точная упаковка запроса: form-urlencoded vs JSON body; поведение `isEncode`; формат ответа (расшифровка ответа AES тем же ключом? — `aesDecrypt` есть).
3. Upload-контракт (`sino-cgm/v2/user-apparatus/save`, `user-device/page`, heartbeat) — только рантайм: распаковка dex из памяти (тот же метод, что в CE-handoff), либо blutter_frida.js hook на реальном устройстве с RU-аккаунтом.
