<!--
SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Проверки аудита

Дата: 2026-10-04, Asia/Vladivostok. Исходная ревизия: `baaca43d5b03139b5b68b172cb1dc61fe68972ab`.
Общий Gradle-прогон проверял дерево `98d845819087af3c9bc778c1b9592ec60bc19f02`.
После него исправлен gplay manifest overlay и усилен Python-скрипт; окончательное дерево кода
до добавления документации: `c9cb96c4377ab54b3f8e7510760b160b3c4fb914`.
Kotlin/Java, тесты и generic manifest между этими деревьями не изменялись.
Документационный commit не изменяет исполняемый код.

## Окружение

- Linux x86-64, около 10 ГБ RAM, без Android-устройства/эмулятора.
- Eclipse Temurin JDK 21.0.12.1; Gradle Wrapper 9.7.1.
- Android SDK API 37.0, Build Tools 36.0.0 (AGP) и 37.0.0 (проверка APK).
- Реальные зависимости проекта получены из заявленных Maven repositories; версии не менялись.
- Gradle использовал доверенные сертификаты хоста (`/etc/ssl/certs/java/cacerts`) для сетевого прокси.
  Проверка TLS не отключалась.

## Результаты

| Проверка | Результат |
|---|---|
| `detekt` | PASS по существующему порогу 114; в отчёте 107 замечаний, новых в изменённых файлах нет |
| `ktlintCheck` | PASS |
| `:app:testGenericDebugUnitTest` | 764 теста: 763 passed, 1 skipped, 0 failures, 0 errors; 99 test suites |
| `TrustManagerTest` | 9/9 passed, в том числе 4 новые проверки ожидания |
| `LoggingHttpInterceptorTest` | 3/3 passed |
| `AttachmentFileWriterTest` | 5/5 passed |
| `MediaStoreFileWriterTest` | 7/7 passed, включая API 28 и API 33 |
| `:app:assembleGenericRelease` | PASS; создан unsigned release APK |
| `:app:assembleGplayDebug` | PASS; создан debug APK |
| Source/merged backup policy | PASS |
| `git diff --check` | PASS |

Всего добавлено 19 тестов. Существующие тесты cookie isolation, remote wipe, URL guards,
chat synchronization, Room, signaling и других подсистем выполнялись в общем generic-наборе.
Это не означает выполнения всего gplay unit-набора или instrumented Android tests.
Единственный пропуск — уже помеченный `@Ignore` тест
`LocalStateBroadcasterMcuTest.testStateSentWithExponentialBackoffWhenAnotherParticipantAdded`.
Этот PR не добавляет пропусков.
Итог общего Gradle-прогона: `BUILD SUCCESSFUL in 9m 34s`, 147 actionable tasks.
В release APK проверены package `com.nextcloud.talk2`, versionCode `250010010`, minSdk 26,
targetSdk 36, `allowBackup=false` и ссылки на обе XML-политики. DEBUGGABLE не включён.
Отдельная проверка собранного gplay manifest обнаружила унаследованное `allowBackup=true` в overlay.
Переопределение исправлено, скрипт теперь также проверяет все source-set manifests;
для этого изменения выполнена отдельная повторная сборка `:app:assembleGplayDebug`.

## Команды заключительного прогона

```bash
./gradlew detekt ktlintCheck :app:testGenericDebugUnitTest \
  :app:assembleGenericRelease :app:assembleGplayDebug \
  --continue --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  -Dorg.gradle.jvmargs='-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
python3 scripts/check_fork_backup_policy.py
python3 scripts/check_fork_backup_policy.py --manifest \
  app/build/intermediates/merged_manifests/genericRelease/processGenericReleaseManifest/AndroidManifest.xml
python3 scripts/check_fork_backup_policy.py --manifest \
  app/build/intermediates/merged_manifests/gplayDebug/processGplayDebugManifest/AndroidManifest.xml
git diff --check
```

В этой среде тестовая JVM Robolectric не смогла разрешить Maven hostname при встроенной загрузке SDK.
Официальные артефакты `org.robolectric:android-all-instrumented:13-robolectric-9030017-i7`
и `9-robolectric-4913185-2-i7` были заранее загружены из Maven Central в отдельный временный каталог.
Локальный Gradle init script передавал test tasks параметры `robolectric.offline=true`
и `robolectric.dependency.dir` с этим каталогом. В репозиторий этот обход окружения не добавлен;
на машине с обычным сетевым доступом Robolectric загружает свои SDK автоматически.

Первый проверочный прогон остановился на KtLint; замечания в новых файлах исправлены.
Параллельный прогон нескольких вариантов с исходной настройкой Kotlin daemon 6 ГБ завершился
исчезновением Gradle daemon; причиной могло быть ограничение памяти среды.
Повторная incremental-компиляция genericDebug также выдала дублирующиеся объявления в неизменённых
файлах. Полная компиляция с `kotlin.incremental=false` прошла без изменения этих исходников.
Поэтому окончательный прогон использует один worker и in-process Kotlin compiler.
Эти промежуточные неудачи не скрываются и не учитываются как успешные проверки.

## Границы проверки

Не выполнены: вход на сервер пользователя, физические звонки, MCU/no-MCU device matrix,
FCM/UnifiedPush после Doze/перезагрузки, OEM backup/D2D, upgrade существующей установки,
замеры батареи/CPU/heap на телефоне, самостоятельный аудит native-библиотек и всех зависимостей.
Release APK не подписывался постоянным ключом владельца. Ключ должен быть создан и сохранён владельцем
по [инструкции](BUILD_AND_DEPLOY_RU.md).

Оба PR остаются draft для человеческого ревью и проверки на устройствах.
Upstream-кандидат не отправлялся в организацию Nextcloud; DCO-подпись за пользователя не добавлялась.
