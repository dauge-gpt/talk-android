<!--
SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Сборка, подпись и распространение APK своего форка

Исходная точка инструкции: `baaca43d5b03139b5b68b172cb1dc61fe68972ab`,
Nextcloud Talk Android `25.1.0 Alpha 10`. Это ветка разработки, а не стабильный релиз.
Перед распространением нужен прогон на тестовом сервере и физических устройствах.
Результаты выполненных проверок приведены в [отчёте аудита](AUDIT_RU.md).

## 1. Что устанавливаем

| Компонент | Значение из проекта | Где проверять при обновлении |
|---|---|---|
| JDK для Gradle daemon | 21 | `gradle/gradle-daemon-jvm.properties` |
| Языковой уровень Java | 17 | `app/build.gradle.kts`, `compileOptions` |
| Gradle | 9.7.1, только Wrapper проекта | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.0 | `build.gradle` |
| Kotlin / Compose compiler plugin | 2.4.10 | `build.gradle`, `app/build.gradle.kts` |
| Android compile SDK | API 37 | `app/build.gradle.kts` |
| Android target SDK | API 36 | там же |
| Минимальная ОС телефона | Android 8.0, API 26 | там же |
| Build Tools | 36.0.0 выбраны AGP при проверке; 37.0.0 подходят для ручной подписи | вывод Gradle и SDK Manager |

Используйте Android Studio, поддерживающую AGP 9.4.0, Git, JDK 21 и Android SDK.
Android Studio можно использовать для редактирования, SDK Manager и подключения телефона;
сборка из консоли воспроизводит команды ниже. Отдельная установка Gradle не нужна.
Для рабочего места разумно выделить 16 ГБ RAM и несколько десятков ГБ свободного диска.
На сервере с меньшей памятью ограничивайте число Gradle workers.

Старые указания API 29 / Build Tools 30 в общем `SETUP.md` не соответствуют этой ревизии.
При следующем обновлении сверяйте таблицу с исходниками, не заменяйте версии зависимостей наугад.

## 2. Получить исходники

```bash
git clone --branch main https://github.com/dauge-gpt/talk-android.git
cd talk-android
git remote add upstream https://github.com/nextcloud/talk-android.git
git status --short
git rev-parse HEAD
```

`main` — интеграционная ветка форка. Ветка upstream называется `master`.
Ветка `main` создана от исходного `master`; настройка default branch на GitHub пока остаётся `master`.
После принятия полного PR можно выбрать `main` основной веткой в настройках своего репозитория.
Приведённый `clone --branch main` выбирает её независимо от этой настройки.
До принятия полного PR для проверки его изменений:

```bash
git fetch origin
git switch --track origin/codex/android-audit
```

После принятия PR возвращайтесь на `main` и делайте `git pull --ff-only`.
Не выполняйте `git pull upstream master` в рабочей ветке без просмотра изменений.
Сначала `git fetch upstream`, затем отдельная ветка обновления и PR.

## 3. Настроить SDK и JDK

В SDK Manager установите Android API 37, Android SDK Build Tools 36.0.0 и 37.0.0,
Platform Tools и Command-line Tools. На момент проверки каталог платформы назывался
`platforms;android-37.0`; имя показывается в `sdkmanager --list`.

Ubuntu / Linux, после установки JDK 21 и Command-line Tools:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager --list
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'build-tools;37.0.0' 'platform-tools'
sdkmanager --licenses
./gradlew --version
```

Просмотрите и примите лицензии SDK самостоятельно. В новых Command-line Tools `sdkmanager`
может перенаправлять к Android CLI; используйте соответствующие команды установленной версии.
`JAVA_HOME` должен указывать на каталог JDK, а не на `bin`.

Windows 10, PowerShell; подставьте фактический каталог установленного JDK:

```powershell
$env:JAVA_HOME = 'C:\Tools\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --list
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
    'platforms;android-37.0' 'build-tools;36.0.0' 'build-tools;37.0.0' 'platform-tools'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
.\gradlew.bat --version
```

В Android Studio выберите JDK 21 в настройках Gradle. Созданный Studio файл `local.properties`
с путём к SDK не коммитьте. В консольной сборке достаточно корректного `ANDROID_HOME`.

## 4. Выбрать вариант приложения

| Вариант | Package ID | Уведомления / ограничения |
|---|---|---|
| `generic` | `com.nextcloud.talk2` | UnifiedPush; настройка дистрибьютора обязательна для фоновых push |
| `gplay` | `com.nextcloud.talk2` | Firebase / Google Play services; это вариант сборки, установка APK не требует маркета |
| `qa` | `com.nextcloud.talk2.qa` | Для временных проверок; versionCode/versionName равны 1, не подходит как готовый канал обновлений |

Для первого самостоятельного развёртывания удобен `generic` и отдельно настроенный UnifiedPush.
См. [UnifiedPush](../unifiedPush.md) и [диагностику уведомлений](../notifications.md).
Вариант `generic` также не содержит MediaPipe-эффекты фона видеозвонка из `gplay`.

У `gplay` Firebase-параметры находятся в `app/src/main/res/values/setup.xml`.
Не считайте, что чужой Firebase-проект автоматически поддерживает новый package ID и новый сертификат.
Для отдельного корпоративного приложения нужно согласованно настроить собственный Firebase-проект
и push-инфраструктуру либо использовать UnifiedPush. Сборка APK сама по себе работоспособность push не доказывает.

## 5. Проверить проект и собрать

Linux:

```bash
./gradlew detekt ktlintCheck
./gradlew :app:testGenericDebugUnitTest
python3 scripts/check_fork_backup_policy.py
./gradlew :app:assembleGenericDebug
./gradlew :app:assembleGenericRelease
```

Windows PowerShell:

```powershell
.\gradlew.bat detekt ktlintCheck
.\gradlew.bat :app:testGenericDebugUnitTest
py -3 scripts/check_fork_backup_policy.py
.\gradlew.bat :app:assembleGenericDebug
.\gradlew.bat :app:assembleGenericRelease
```

Для `gplay` замените `Generic` на `Gplay`. APK находятся в:

- `app/build/outputs/apk/generic/debug/` — debug, подписан отладочным ключом;
- `app/build/outputs/apk/generic/release/` — release, пока без подписи;
- `app/build/outputs/apk/gplay/release/` — соответствующий release Google-варианта.

Точное имя проверьте по содержимому каталога и `output-metadata.json`.
В этой ревизии release signingConfig не задан; штатный результат — `app-generic-release-unsigned.apk`.
Отладочный APK не распространяйте как production: у него включён debugging и другой ключ.
В release сейчас `isMinifyEnabled = false`. Не включайте R8 одновременно с выпуском этих исправлений:
сериализация и reflection требуют отдельного регрессионного прогона.

Для небольшого Linux-сервера можно добавить к команде:

```bash
./gradlew :app:assembleGenericRelease --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
```

Учтите отдельную память Kotlin daemon: в проекте для него задано 6 ГБ.
Если несколько JVM не помещаются в RAM, выполняйте варианты последовательно и используйте
`--max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` с достаточным heap Gradle.
При странных duplicate/overload diagnostics после incremental-пересборки сначала проверьте
полную компиляцию с `-Pkotlin.incremental=false`, прежде чем менять корректные исходники.

Это локальная сборка: GitHub Actions для неё не нужны. В новом форке оставьте Actions отключёнными,
пока не настроен осознанный процесс CI. Унаследованные workflows содержат специфичные для Nextcloud
runner labels, инфраструктуру публикации и секреты; включать их все кнопкой Enable workflows не следует.

## 6. Создать постоянный ключ разработчика

Ключ создаёт и хранит владелец приложения. Он нужен для всех последующих обновлений.
Пример для Linux:

```bash
mkdir -p "$HOME/.local/share/talk-signing"
chmod 700 "$HOME/.local/share/talk-signing"
keytool -genkeypair -v \
  -keystore "$HOME/.local/share/talk-signing/talk-release.jks" \
  -storetype JKS -alias talk-release -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.local/share/talk-signing/talk-release.jks"
```

На Windows создайте закрытый каталог вне репозитория, например `C:\Private\TalkSigning`,
и ограничьте доступ через свойства безопасности NTFS:

```powershell
New-Item -ItemType Directory -Force 'C:\Private\TalkSigning'
keytool -genkeypair -v `
    -keystore 'C:\Private\TalkSigning\talk-release.jks' `
    -storetype JKS -alias talk-release -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` интерактивно запросит пароли и данные владельца. Не передавайте пароль аргументом команды,
не сохраняйте его в git или истории терминала. Сделайте зашифрованную резервную копию keystore
и отдельно сохраните пароли. Потеря ключа обычно лишает возможности обновлять уже установленный APK.
`Signed-off-by` в git и подпись APK — разные механизмы.

## 7. Выровнять APK, подписать и проверить

Сначала `zipalign`, потом `apksigner`; после подписи содержимое APK менять нельзя.
Пример для Linux, из корня репозитория:

```bash
mkdir -p dist
"$ANDROID_HOME/build-tools/37.0.0/zipalign" -P 16 -f -v 4 \
  app/build/outputs/apk/generic/release/app-generic-release-unsigned.apk \
  dist/talk-generic-aligned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign \
  --ks "$HOME/.local/share/talk-signing/talk-release.jks" \
  --ks-key-alias talk-release \
  --out dist/talk-generic-release.apk dist/talk-generic-aligned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs dist/talk-generic-release.apk
"$ANDROID_HOME/build-tools/37.0.0/zipalign" -c -P 16 -v 4 dist/talk-generic-release.apk
sha256sum dist/talk-generic-release.apk
```

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force dist
& "$env:ANDROID_HOME\build-tools\37.0.0\zipalign.exe" -P 16 -f -v 4 `
    app\build\outputs\apk\generic\release\app-generic-release-unsigned.apk `
    dist\talk-generic-aligned.apk
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" sign `
    --ks 'C:\Private\TalkSigning\talk-release.jks' --ks-key-alias talk-release `
    --out dist\talk-generic-release.apk dist\talk-generic-aligned.apk
& "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs `
    dist\talk-generic-release.apk
& "$env:ANDROID_HOME\build-tools\37.0.0\zipalign.exe" -c -P 16 -v 4 dist\talk-generic-release.apk
Get-FileHash dist\talk-generic-release.apk -Algorithm SHA256
```

Сохраните SHA-256 **сертификата** из `apksigner` как постоянную идентичность издателя.
SHA-256 **файла** меняется при каждой сборке и подтверждает целостность конкретного APK.
Успешный `zipalign -P 16` не доказывает совместимость всех native `.so` с 16-КБ страницами:
это отдельно проверяется на соответствующем Android-устройстве/эмуляторе.

## 8. Установить и обновлять

Подключите тестовый телефон по USB, включите USB debugging и подтвердите доверие компьютеру:

```bash
adb devices
adb install -r dist/talk-generic-release.apk
adb shell am start -n com.nextcloud.talk2/com.nextcloud.talk.activities.MainActivity
adb shell dumpsys package com.nextcloud.talk2
```

Для нескольких устройств используйте `adb -s SERIAL ...`.
Проверьте package ID, versionCode, отсутствие флага DEBUGGABLE у release и подпись через `apksigner`.
Для следующего релиза увеличьте `versionCode` в `app/build.gradle.kts`, обновите `versionName`,
соберите и подпишите тем же ключом. Схему версий ведите последовательно, учитывая будущие upstream-обновления.

**Ваш ключ не заменяет ключ Nextcloud или F-Droid.** APK с тем же package ID и другой подписью
не установится поверх официального приложения (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
Debug и release с разными ключами также несовместимы для обновления.
Не удаляйте рабочее приложение ради эксперимента: удаление стирает локальные данные.
Первую проверку проводите на отдельном телефоне, эмуляторе или в отдельном Android-профиле.

Для параллельной установки корпоративной версии нужен собственный applicationId.
Это отдельное изменение: проверить FileProvider authorities, permissions, direct share, deep links,
интеграцию с Nextcloud Files и Firebase. `qa` пригоден для временного теста рядом с официальной версией,
но не является готовой production-конфигурацией.

Для распространения без маркета разместите подписанный APK на контролируемом HTTPS-ресурсе
или передайте по доверенному каналу. Рядом укажите версию, commit SHA, SHA-256 файла и сертификата,
изменения, известные ограничения и соответствующие исходники/лицензию GPL-3.0-or-later.
На телефоне пользователь разрешает установку из выбранного источника и открывает APK.
Разрешение можно отключить после установки. Автообновление собственных APK этим проектом не добавлено.

## 9. Проверка перед распространением

1. Чистая установка и обновление предыдущей **вашей** подписанной версии без потери аккаунта.
2. Вход через браузер, QR, повторная авторизация; два аккаунта на одном сервере.
3. Текст, изображения, документы, скачивание, сохранение; отмена и недостаток места.
4. Входящий звонок с погашенным экраном, исходящий звонок, Bluetooth, камера, поворот, PiP.
5. Звонки MCU и без MCU; Wi-Fi → мобильная сеть; отсутствие сети и восстановление.
6. Push после перезагрузки и длительного простоя, Android 13+ notification permission.
7. Принятие/отклонение собственного TLS-сертификата на **тестовом** сервере.
8. Удаление аккаунта, отсутствие старых уведомлений/сессий, проверка backup/D2D правил.
9. Физические устройства API 26/28, 30, 31+ и текущий Android; ARM64 и 16-КБ страницы при наличии.

После переноса телефона или переустановки потребуется повторный вход: форк исключает приватное
состояние из Android cloud backup и device transfer. Уже существующие резервные копии и ранее
экспортированные логи обновление автоматически не удаляет.

## 10. Диагностика сборки и установки

| Симптом | Действие |
|---|---|
| `No defined toolchain download url` / требуется Java 21 | Установить JDK 21 и проверить `JAVA_HOME`; файлы проекта не понижать до Java 17 |
| `SDK location not found` | Проверить SDK Manager, `ANDROID_HOME` или `local.properties` |
| Не найдена платформа 37 | Сверить имя SDK-пакета в `sdkmanager --list`; установить API 37 |
| `PKIX path building failed` | Проверить доверенные CA рабочего прокси; не отключать проверку TLS |
| OutOfMemory / daemon disappeared | Ограничить workers и heap, проверить память и место; повторить без параллельной сборки |
| KtLint / Detekt | Читать `app/build/reports/`; форматировать только свой diff, не маскировать новые замечания |
| Gradle dependency verification failed | Проверить источник/контрольную сумму изменения; не отключать verification целиком |
| `VERSION_DOWNGRADE` | Использовать увеличенный versionCode; не форсировать downgrade на рабочих данных |
| `UPDATE_INCOMPATIBLE` | Проверить package ID и сертификат; это не ошибка сервера Nextcloud |
| APK работает, push отсутствует | Проверить выбранный flavor, UnifiedPush/FCM, разрешения, Doze и настройки сервера |

Если выпуск неудачен, предпочтителен исправленный APK с большим versionCode и тем же ключом.
Откат APK на месте опасен несовместимостью схемы БД; удаление и переустановка допустимы только
после осознанного решения о потере локального состояния.

Первичные источники: [сборка из консоли](https://developer.android.com/build/building-cmdline),
[подпись](https://developer.android.com/tools/apksigner),
[zipalign](https://developer.android.com/tools/zipalign),
[backup](https://developer.android.com/identity/data/autobackup).
