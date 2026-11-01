# Разбор натива iCan: JNI-мост planCalib/startCalib и VMP-контейнер (2026-10-29)

Источник: `~/Downloads/luch-ru-cgm-0.1.0-vc39.apk` (плейтекстовый RU/Luch-билд), извлечённые
`lib/{arm64-v8a,armeabi-v7a,x86_64}/…` и `assets/ijiami_libCgmSdk.VData`.
Инструменты: REA/Ghidra (arm64-профиль), python-разбор ELF-динамики (struct, ELF32/ELF64),
`strings`/`grep -abo`/`xxd`. Все sha256/размеры/оффсеты сняты заново в 2026-10-29.
Документ локальный/приватный (как план-док, вне git-коммитов). Секретов внутри нет: только
адреса, размеры, хэши и структуры — никаких SN/ключей/challenge-байтов.

## 1. Именование: «libCgmSdk.so» — это не файл

- Файл `lib/arm64-v8a/libsinoCgmSdk.so`: читаемый `DT_SONAME` = **`libsinoCgmSdk.so`** — это
  мост (§3–§5).
- Файл `libijiami_libCgmSdk.so` (все ABI) — ijiami-упакованный **загрузчик VMP-ядра**.
  Уточнение к прежней формулировке «DT_SONAME → libCgmSdk.so»: **строка `libCgmSdk.so`
  в байтах загрузчиков не встречается**. v7a-вариант читается как ELF32 и даёт
  **`DT_SONAME` = `libSdkVMP.so`**; у arm64 dyn-секция спрятана UPX (SONAME по байтам нечитаем),
  плейнтекстовые dynstr-обрывки содержат только список зависимостей.
- В рантайме библиотека подписывается **`libSdkVMP.so`** — согласуется с §7.6 плана.

## 2. Инвентарь артефактов

| Файл (в APK) | ABI | Размер, B | SHA-256 | Что это |
|---|---|---|---|---|
| `lib/arm64-v8a/libsinoCgmSdk.so` | arm64 | 30 968 | `1b2da14734cd5a756c6af7807f53ec5d7ec0f90b81f0a7f8433d8fc6e2a683fb` | Тонкий JNI-мост к `libAlg.so`; разобран полностью (§3–§5) |
| `lib/armeabi-v7a/libsinoCgmSdk.so` | arm32 | 5 480 | — (в этой сессии не снимался) | arm32-двойник моста, не разбирался |
| `lib/arm64-v8a/libijiami_libCgmSdk.so` | arm64 | 156 976 | `2f9872d769dcec9ccb98e9214da2dfd67299a0b1b08d04665dee8add4cd42407` | UPX 6.00-загрузчик VMP; DT_NEEDED (dynstr): liblog, libandroid, libz, libc, libm, libdl, **libSdkVMP** |
| `lib/armeabi-v7a/libijiami_libCgmSdk.so` | arm32 | 172 880 | `50f82380fb5606df9906b89615d6fe4b81bbfc2ac3815a28367933dd55779306` | Не UPX, ELF32 читается: **SONAME = `libSdkVMP.so`**; NEEDED: liblog, libandroid, libz, libc, libm, **libstdc++**, libdl; в dynstr есть `JNI_OnLoad`; Thumb-обфуск — осмысленный disasm не получается |
| `lib/x86_64/libijiami_libCgmSdk.so` | x86_64 | 157 096 | `2e1f3e352e5b9b980b9770ef70d478f076ffbf591e0219a264833bc60e315f5d` | UPX (маркеры см. §7); не разбирали |
| `assets/ijiami_libCgmSdk.VData` | — | 194 430 | `cbae47bac40d535839340492bd60761ff90099f1ee1c53594f92e74eb39bf211` | **VMP-контейнер `ivmp` — здесь NCall-натив** (§8) |

## 3. Мост `libsinoCgmSdk.so` (arm64) — ELF-каркас

- Ghidra image base `0x100000`. Секции: `.text` 0x10280c–0x106a0c (16 896 B),
  `.plt` 0x106a10–0x106be0, `.got.plt` 0x10ae70–0x10af60, `.got` 0x10ae48–0x10ae70.
- Читаемая динамика (python-разбор): `DT_SONAME` = `libsinoCgmSdk.so`;
  `DT_NEEDED` (в порядке): `libandroid.so`, `liblog.so`, **`libAlg.so`**, `libm.so`,
  `libdl.so`, `libc.so` — расчёт живёт в `libAlg.so`, мост только маршалит.
- Всего процедур 139, строк 130 — почти всё это статически слинкованный LLVM libunwind (§6).
- **Экспорты (dynsym) — ровно 3**:
  - `Java_com_sinocare_cgm_cgm_1sdk_util_AlgManager_planCalibJNI` @ 0x10286c
  - `Java_com_sinocare_cgm_cgm_1sdk_util_AlgManager_startCalibJNI` @ 0x102b8c
  - `_ZN7_JNIEnv9NewObjectEP7_jclassP10_jmethodIDz` (`_JNIEnv::NewObject`, vararg-шейма) @ 0x102af0
- `JNI_OnLoad` / `RegisterNatives` **нет** — привязка стандартная, по JNI-именам.

## 4. `.got.plt`-слоты моста (кто откуда разрешается)

| Слот | Метка Ghidra | Куда |
|---|---|---|
| 0x10aeb0 | `PTR_planCalib_0010aeb0` | PLT 0x106a80 → `<EXTERNAL>::AlgNamespace::planCalib` (из `libAlg.so`) |
| 0x10aeb8 | `PTR_operator.delete_0010aeb8` | `operator delete` (libc++) |
| 0x10aec0 | `PTR_NewObject_0010aec0` | PLT 0x106aa0 → `_JNIEnv::NewObject` (локальная vararg-шейма) |
| 0x10aed0 | `PTR_startCalib_0010aed0` | PLT 0x106ac0 → `<EXTERNAL>::AlgNamespace::startCalib` (из `libAlg.so`) |
| 0x10aed8 | `PTR___cxa_allocate_exception_0010aed8` | C++ exceptions |

Внешние точки в EXTERNAL-сегменте Ghidra: 0x110028 = `AlgNamespace::planCalib`,
0x110040 = `AlgNamespace::startCalib`. Стек-страховка: канарейка `tpidr_el0+0x28`
и `__stack_chk_fail` в обоих JNI.

## 5. JNI-функции моста (Ghidra, arm64)

Псевдокод приведён с точечными купюрами (обозначены «…»); артефакты варарг-ABI
(`(double)`-касты, `unaff_x25`) — норма декомпилятора. JNIEnv-функции вызываются по
офсетам vtable: `+0x30` FindClass, `+0x108` GetMethodID, `+0x558` GetArrayLength,
`+0x5e8` GetFloatArrayElements, `+0x628` ReleaseFloatArrayElements.

### 5.1. `planCalibJNI` @ 0x10286c

```
uVar3 = (**(code **)(*(long *)param_6 + 0x558))(param_6,param_8);           // GetArrayLength(bgList)
__src = (void *)(**(code **)(*(long *)param_6 + 0x5e8))(param_6,param_8,0); // GetFloatArrayElements(bgList, null)
if (uVar3 == 0) { /* нулевые векторные слоты */ }
else {
  if ((int)uVar3 < 0) { …; goto LAB_00102aec; }                            // защита от отрицательного размера
  __n = -(ulong)(uVar3 >> 0x1f) & 0xfffffffc00000000 | (long)pvVar6 << 2;  // len*4
  pvVar6 = operator_new(__n);
  memcpy(pvVar6,__src,__n);                     // первая копия элементов
  …
  local_b8 = operator_new(__n);                 // вторая копия — паттерн копирующего конструктора vector<float>
  local_a8 = (void *)((long)local_b8 + __n);
  memcpy(local_b8,__src,__n);
  local_b0 = local_a8;
}
AlgNamespace::planCalib(&local_a0,param_1,param_2,param_3,param_4,param_5,&local_b8);
if (local_b8 != (void *)0x0) { …; operator_delete(local_b8,…); }           // RAII-деструктор вектора
p_Var4 = (**(code **)(*(long *)param_6 + 0x30))(param_6,"com/sinocare/cgm/cgm_sdk/bean/AlgParameterBean");
uVar5 = (**(code **)(*(long *)param_6 + 0x108))(param_6,p_Var4,"<init>","(FZIFFF)V");
uVar5 = _JNIEnv::NewObject(param_6,p_Var4,(double)local_a0,(double)local_94,(double)fStack_90,
                           (double)local_8c,uVar5,(ulong)local_9c,(ulong)local_98);
(**(code **)(*(long *)param_6 + 0x628))(param_6,param_8,__src,0);           // ReleaseFloatArrayElements(bgList, elems, 0)
if (pvVar6 != (void *)0x0) { …; operator_delete(pvVar6,…); }
// локалы-результат: local_a0(f@0) local_9c(b@4) local_98(i@8) local_94(f@0xC) fStack_90(f@0x10) local_8c(f@0x14)
```

Коротко: массив `bgList` (jfloatArray) → сырые элементы → копия в `vector<float>`
(двойной `operator_new(len*4)` + `memcpy`), затем **5 скаляров + ссылка на вектор** уходят в
`AlgNamespace::planCalib` (результат — 24-байтовая структура на стеке), struct →
`com/sinocare/cgm/cgm_sdk/bean/AlgParameterBean` через ctor `"(FZIFFF)V"`.
Массив освобождается только в конце (`ReleaseFloatArrayElements`, mode `0`).

Структура результата (24 B) → конструктор `AlgParameterBean(FZIFFF)V`:

| Оффсет | Тип | Аргумент ctor |
|---|---|---|
| 0x00 | float | F (1-й) |
| 0x04 | bool | Z |
| 0x08 | int | I |
| 0x0C | float | F |
| 0x10 | float | F |
| 0x14 | float | F |

Семантика полей бина — на Java-стороне dex (`AlgParameterBean`); в этой заметке не
восстанавливалась. Точный порядок Java-параметров `AlgManager.planCalib` (какие 5
float'ов и где стоит `bgList`) — из dex, здесь не разбирался.

### 5.2. `startCalibJNI` @ 0x102b8c

```
AlgNamespace::startCalib(param_1,param_6 != '\0',param_7,param_2,param_3);
p_Var2 = (**(code **)(*(long *)param_4 + 0x30))(param_4,"com/sinocare/cgm/cgm_sdk/bean/AlgParameterBean");
uVar3 = (**(code **)(*(long *)param_4 + 0x108))(param_4,p_Var2,"<init>","(FZIFFF)V");
uVar3 = _JNIEnv::NewObject(param_4,p_Var2,(double)local_50,(double)local_44,(double)fStack_40,
                      (double)local_3c,uVar3,(ulong)local_4c,(ulong)local_48);
// локалы-результат: local_50(f@0) local_4c(b@4) local_48(i@8) local_44(f@0xC) fStack_40(f@0x10) local_3c(f@0x14)
```

- Массивов нет: **5 flat-аргументов** → `AlgNamespace::startCalib` (bool передаётся как
  `param != 0`). Имена аргументов по Java-обёртке SDK (установлены ранее в этом репо):
  `caliK`, `caliAdjustBool`, `caliAdjustNo`, `newBg`, `calik0` — в порядке вызова C.
- Тот же бин `AlgParameterBean` и тот же ctor `"(FZIFFF)V"`; результат-структура та же (24 B).
- PLT-callees по выгрузке REA: 0x106aa0 (`_JNIEnv::NewObject`), 0x106ab0 (неименованный
  PLT-слот, по контексту — `__stack_chk_fail`), 0x106ac0 (`AlgNamespace::startCalib`).

## 6. «Диспетчеры» — это не NCall, это LLVM libunwind

`switchD_001042f0` (caseD_0..0x2b), `switchD_00105634` (caseD_0..0x94), `FUN_001053b8`
(парсер ULEB128-подобный), fprintf/abrt-пути — **статически слинкованный LLVM libunwind**
(DWARF CFI-интерпретатор) при `.eh_frame_hdr` 0x101c78–0x101e2c и `.eh_frame`
0x101e30–0x10280c. Таблиц «команд»/строкового протокола NCall в байтах нет; экспортов 3,
все по JNI-именам. ⇒ тезис плана §7.6 «в нативе команд нет» подтверждён байт-в-байт.

## 7. Загрузчики `libijiami_libCgmSdk.so` — UPX-картина

Маркеры (файл-оффсеты, decimal / HEX):

| ABI | Маркер | Оффсет |
|---|---|---|
| arm64 | `UPX!` | 10628 (0x2984) |
| arm64 | `$Info` | 133136 (0x20810) |
| arm64 | `$Id` | 133215 (0x2085F) |
| arm64 | `UPX!` | 135213 (0x2102D) |
| arm64 | `ZlwUPX!` (битый хвост) | 156335 (0x262AF) |
| x86_64 | `UPX!` | 10516 (0x2914) |
| x86_64 | `UPX!` | 140803 (0x22603) |
| x86_64 | `7UPX!` | 156453 (0x26325) |

- arm64: UPX 6.00, фейковые phdr/vaddr ⇒ Ghidra/REA видит всё как DAT;
  стоковый `upx -d` отвергает файл (битый хвост, §7.6 плана).
- v7a: **не UPX**, ELF32 читается; `DT_SONAME` = `libSdkVMP.so`; `DT_NEEDED`:
  liblog, libandroid, libz, libc, libm, `libstdc++`, libdl; `JNI_OnLoad` в dynstr;
  контент — Thumb-обфуск, осмысленный disasm не получается (гейт Ghidra).
- Роль загрузчика: self-представление как **`libSdkVMP.so`** и подтяжка VMP-ядра из assets.

## 8. VMP-контейнер `assets/ijiami_libCgmSdk.VData` — здесь NCall

- Магия `ivmp` @ 0x00 (+ 0x0A), ASCII-версия `02\0` @ 0x05; 0x08–0x1F — нули.
- Таблица полей (LE u64 по 8 B, снято `xxd` до 0xA8):

| Оффсет | Значение | Оффсет | Значение |
|---|---|---|---|
| 0x20 | `0x676CD` | 0x68 | `0x6C3` |
| 0x28 | `0xB0` | 0x70 | `0x1F468` |
| 0x30 | `0xB0` | 0x78 | `0x435` |
| 0x38 | `0xDCD` | 0x80 | `0x59544` |
| 0x40 | `0x15BD4` | 0x88 | `0x4EC` |
| 0x48 | `0xF9` | 0x90 | `0x63D4C` |
| 0x50 | `0x15FB8` | 0x98 | `0x65` |
| 0x58 | `0x360` | 0xA0 | `0x2F6CE` |
| 0x60 | `0x18838` |  |  |

- `0x2F6CE` близко к размеру минус 0xD0 (194 430 = 0x2F79E) — похоже на оффсет хвоста/
  footer, назначение полей **не установлено** (не отвлекались).
- Содержимое — VMP-байткод ijiami, статически **не разобрано**. Путь восстановления —
  только живой runtime-dump: ловим момент материализации `libSdkVMP.so` (план §7.6/§7.9).

## 9. Выводы

1. В либах APK **нативного NCall нет**: `libsinoCgmSdk.so` — тонкий JNI-мост
   (подтверждён байт-в-байт), `libijiami_libCgmSdk.so` — UPX/Thumb-загрузчики.
2. Расчёт `planCalib`/`startCalib` физически живёт в `libAlg.so` (разобран в §1.4 плана,
   порт в JNG сверен golden-векторами) — мост лишь тонкий JNI-слой над ним.
3. Единственный носитель NCall — `assets/ijiami_libCgmSdk.VData` (`ivmp`/`02`), который
   в рантайме становится `libSdkVMP.so`. Если он вдруг понадобится — только runtime-dump через
   Frida (ловля `dlopen`/mmap), статически из APK его не восстановить.
4. Для воспроизведения логики моста в JNG достаточно повторить семантику:
   `vector<float>` из измерений + 5 скаляров → план-расчёт (порт есть) → бин результата (F,Z,I,F,F,F).

## 10. Связанные материалы

- План: `docs/superpowers/plans/2026-10-19-ican-calibration-plan.md` — §7.6 (мост/VMP),
  §7.9 (runtime-dump путь), §1.4 (libAlg.so аудит и порт).
- Заметки correctCode: `docs/ican/ICAN-NOTES_2026-10-18-correctcode-investigation.md`.

— 2026-10-29, разбор REA/Ghidra (arm64), ассистентская сессия JNG.
