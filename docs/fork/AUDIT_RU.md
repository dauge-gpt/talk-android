<!--
SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Аудит форка Nextcloud Talk Android

Дата: 4 октября 2026, Asia/Vladivostok.
Базовая ревизия: `baaca43d5b03139b5b68b172cb1dc61fe68972ab`.
Репозиторий: `dauge-gpt/talk-android`; родитель: `nextcloud/talk-android`.

## Объём и достоверность

В исходниках примерно 1 000 Kotlin/Java-файлов и 148 тысяч строк с тестами.
Выполнены обзор архитектуры и конфигурации, статическое сканирование исходников,
ручное прослеживание критических потоков данных и целевой рефакторинг с регрессионными тестами.
Это не построчная ручная проверка каждого файла и не сертификация безопасности приложения.
Нативные реализации WebRTC/SQLCipher, сервер Nextcloud, TURN/HPB, Firebase и код всех транзитивных
зависимостей не прошли самостоятельный аудит в рамках этой работы.

Проверка исходников и воспроизводимый unit-тест отличаются от эксплуатации на реальном телефоне.
Ни одному наблюдению ниже не присваивается CVE или статус подтверждённой удалённой уязвимости.
Публикация security report в Nextcloud требует независимой проверки человеком и соблюдения `SECURITY.md`.

## Карта анализа

| Подсистема | Прослеженные участки | Главные результаты |
|---|---|---|
| Сборка и supply chain | Wrapper, root/app Gradle, verification metadata, flavors, workflows | JDK 21; compileSdk 37; старый SETUP не соответствует коду; подпись release не настроена |
| Вход и авторизация | BrowserLoginActivity, NetworkLoginDataSource, LoginRepository, UriUtils | Внешний браузер; origin/path-проверки URL; отдельные риски OTP-клиента и обработки ответов |
| HTTP/TLS | RestModule, TrustManager, KeyManager, AccountCookieInterceptor, RemoteWipeInterceptor | Аккаунтные cookies уже изолируются; устранено активное ожидание TLS; ограничен HTTP-log |
| Локальные данные | TalkDatabase, UsersDao, AccountRemovalWorker, FileUtils, manifest, backup XML | Ограничены backup/D2D; ключ БД, удаление кэшей и восстановление БД требуют дальнейшей работы |
| Вложения | FileViewerUtils, DownloadFileToCacheWorker, SaveFileToStorageWorker, UploadAndShareFilesWorker, FileUploader, ChunkedFileUploader | Исправлены транзакционность загрузки, владение потоками и подтверждение сохранения |
| Фоновые задачи | Catch-up, read marker, push, WorkManager scheduling | Есть ограничения повторов и coalescing; замечены блокировки/широкие catch |
| Звонки и signaling | WebSocketConnectionHelper, WebSocketInstance, PeerConnectionWrapper, MCU/no-MCU структура | Нужен отдельный lifecycle-прогон; обнаружен подозрительный путь удаления отключённого WebSocket |
| UI/lifecycle | BaseActivity, login, media viewer, FileViewerUtils, VoiceMessageMediaService | Есть синхронное ожидание фоновой работы из UI; полного device-прогона нет |

## Исправления в полном PR

### A1. Ожидание решения о сертификате без расходования ядра CPU

`TrustManager.isCertInTrustStore` опрашивал KeyStore в пустом цикле до 15 секунд.
При нескольких HTTP-соединениях одновременно ожидать могли несколько потоков.
Использованы монитор, `timedWait`, монотонное время и `notifyAll` после изменения доверенного хранилища.
Чтение и запись KeyStore синхронизированы. Пробуждение проверяет именно ожидаемый сертификат.
Interrupt восстанавливается, и проверка завершается отказом.

Срок ожидания и существующая политика пользовательского доверия сохранены.
Тесты проверяют состояние TIMED_WAITING, принятие сертификата, принятие другого сертификата,
interrupt и истечение срока. Падение нагрузки на реальном устройстве ещё не измерено;
основание вывода — устранение активного цикла и проверка состояния потока.
Это единственная тема upstream-кандидата.

### A2. HTTP-диагностика без тел сообщений и учётных данных

`LoggingHttpInterceptor` при DEBUG использовал `HttpLoggingInterceptor.Level.BODY` и редактировал
только Authorization/Proxy-Authorization. Тела login/poll и чатов, cookies и URL не покрывались
этой редакцией; BODY также считывает поток ответа для журналирования.

Теперь собственный interceptor записывает HTTP status и время получения ответа; при IOException —
только тип исключения. Он не читает и не буферизует request/response body, URL, headers или текст exception.
Response и исходное исключение возвращаются вызывающему коду без подмены.
Отдельно убрана прямая печать push-токена из обоих gplay-путей и общего `PushUtils`.

Тесты проверяют отсутствие приватных маркеров в HTTP-записи, отсутствие чтения streaming body,
неизменность response/exception и отключённый DEBUG.
Это не универсальная очистка всех application logs: `Log.*` в других подсистемах ещё содержит
идентификаторы, URL и сообщения исключений. Ранее сохранённые или экспортированные логи остаются у владельца.

### A3. Ограничение Android backup и переноса данных

В исходной конфигурации `allowBackup=true`, legacy backup XML пуст, Android 12+ extraction rules отсутствуют.
В общем и gplay manifests выставлен `allowBackup=false` и добавлены исключения всех доменов приватного состояния
для legacy backup, cloud backup и device-to-device transfer.
Проверены source manifests и собранные merged manifests обоих вариантов; `scripts/check_fork_backup_policy.py`
позволяет повторить проверку. Правила согласованы с документацией Android.

Изменение затрагивает поведение продукта: перенос приложения не восстанавливает аккаунты, настройки
и локальные черновики. После переустановки/переноса нужен вход; синхронизируемые серверные данные
получаются с Nextcloud. Экспортированные самим пользователем файлы в общей памяти остаются вне этой политики.
Для конкретного OEM всё равно нужен практический backup/D2D-тест.

### A4. Целостность и освобождение ресурсов при скачивании

Ранее input/output streams закрывались только в успешном конце загрузки; временный файл имел
детерминированное имя `<filename>_`. Две загрузки одного объекта могли писать в один temporary file.
HTTP error body и ответ при отклонённом имени также не закрывались во всех ветках.

`AttachmentFileWriter` владеет входным потоком, создаёт уникальный temporary file рядом с назначением,
закрывает потоки, публикует результат через rename только после успешного копирования и удаляет остаток
в finally. Проверяется остановка между чтениями и до публикации. Worker закрывает HTTP bodies,
обрабатывает IOException и использует монотонный счётчик времени для progress; процент ограничен 0–100.
Тесты покрывают успех, ошибку чтения, отмену, ошибку публикации и перекрывающиеся загрузки.

Блокирующее сетевое чтение всё ещё ограничено timeout HTTP-клиента: проверка `isStopped` между чтениями
не равна немедленному `Call.cancel()` во время заблокированного read. Аварийное завершение всего процесса
может оставить `.part`, несмотря на finally; общего сборщика старых файлов этот PR не добавляет.

### A5. Сохранение в MediaStore подтверждается только после записи

Worker раньше возвращал success при `insert == null` или `openOutputStream == null`, не закрывал input,
оставлял запись при ошибке и сканировал исходный cache-файл вместо результата.
Выделен `MediaStoreFileWriter`: открытие/закрытие потоков, проверка null, rollback созданной записи
при ошибке; Android 10+ использует IS_PENDING до завершения записи. Сканирование исходного cache убрано.
RELATIVE_PATH задаётся только с Android 10.

Тесты проверяют полный результат, null insert/output, исчезнувший source, ошибку публикации,
ошибку rollback с сохранением исходного exception и отсутствие новых колонок на API 28.
Android 8–9 сохранение немедийных документов через существующий file URI в `getUriByType`
остаётся отдельной проблемой; эта ветка теперь сообщает ошибку, а не фиктивный успех.

## Оставшиеся риски и следующий приоритет

Приоритет означает порядок работы, а не оценку CVSS.

| Приоритет | Наблюдение и основание | Что требуется |
|---|---|---|
| Высокий | Ключ SQLCipher берётся из строкового ресурса `nc_talk_database_encryption_key` в `TalkDatabase.buildDatabase` | Проектирование per-install ключа с Android Keystore, миграция существующей БД, тесты потери/смены ключа. Простая замена строки сделает старую БД нечитаемой |
| Высокий | `TalkDatabase.build` удаляет БД при любом пойманном SQLiteException | Разделить corruption, неверный ключ, блокировку, заполненный диск; сохранение оригинала и явный recovery. Не менять без migration/recovery-тестов |
| Высокий | `AccountRemovalWorker` запускает часть удаления через асинхронный Rx subscribe, а WorkManager может уже получить success | Сделать удаление завершённой транзакцией жизненного цикла; проверить порядок remote-wipe acknowledgement и отказ сети |
| Высокий | Кэш вложений использует имя либо fileId без account/server namespace; AccountRemovalWorker не очищает весь соответствующий файловый кэш | Единая схема ключа account+fileId, перенос всех viewer/share/download путей и удаление данных конкретного аккаунта |
| Высокий | `WebSocketConnectionHelper.deleteExternalSignalingInstanceForUserEntity` удаляет map entry только если isConnected; reconnect-путь может сохранить объект | Терминальное close/dispose, запрет поздних reconnect callbacks, тесты logout во время reconnect и оба MCU/no-MCU сценария |
| Средний | `network_security_config.xml` допускает HTTP и пользовательские CA; ручное доверие сертификату обходит mismatch hostname | Для корпоративного deployment определить HTTPS/CA-политику. Не отключать совместимость с локальными серверами без решения владельца |
| Средний | `RemoteWipeInterceptor` удаляет аккаунт по подходящему 401 даже если wipe-check не подтвердил wipe | Уточнить контракт upstream для revoked app-password и временных 401; проверить, что это ожидаемая политика, а не объявлять её уязвимостью без контекста |
| Средний | OTP в `NetworkLoginDataSource` создаёт новый OkHttpClient вместо инъецированного; JSON ошибки обрабатываются неодинаково | Тесты proxy/client certificate/self-signed OTP и malformed login responses; разделить допустимые настройки клиента и account wipe interceptor |
| Средний | `allowMainThreadQueries`, `runBlocking` в preferences/UI и `.get()` для WorkManager в FileViewerUtils | Измерить main-thread stalls, переносить по контрактам на coroutines/Flow, не удалять разрешение main-thread queries механически |
| Средний | `runCatching` может проглотить CancellationException в worker/repository; `pullMessagesFlow` при MAX_PULL_ATTEMPTS=5 делает четыре попытки | Отдельные virtual-time tests отмены/повторов; rethrow CancellationException на всех затронутых границах |
| Средний | Обычный FileUploader читает файл в byte array | В текущем UploadAndShareFilesWorker chunking начинается выше 1 MiB, поэтому это не доказанная неограниченная RAM-утечка. Проверить изменённые provider URI и future call sites перед переходом на streaming |
| Средний | `SaveFileToStorageWorker` использует file URI для документов на API 26–28 | SAF либо корректная legacy filesystem ветка с permissions и безопасным именем; тестировать на устройстве |
| Средний | Остальные `Log.*` содержат room IDs, URL, часть payload и exception messages | Согласованная политика логов и тесты экспорта; запрет публикации реальных логов с аккаунтами в issue |
| Средний | Большие chat/call activities, mutable state, смешение RxJava и coroutines | Профилирование и поэтапное разделение владения ресурсами; сохранить signaling-семантику |
| Низкий | У Wrapper нет distributionSha256Sum; release без R8; lint/SpotBugs не являются жёстким gate | Проверить checksum по официальному источнику, постепенно укрепить gates после фикса baseline; R8 и native 16-KB проверить отдельно |

## Уже имеющиеся полезные меры

- `UriUtils.isOnServer` сравнивает scheme/host/port и границу пути, а не простой host prefix.
- `AccountCookieInterceptor` разделяет cookie stores по credentials; удаление аккаунта очищает его store.
- FileProvider не экспортирован, предоставляет ограниченные cache/log-подкаталоги.
- `FileUtils.resolveFileInDirectory` проверяет basename, separators и canonical containment.
- В app Gradle включён dependency verification metadata; большинство workflow actions зафиксированы SHA.
- Background catch-up имеет coalescing, cooldown и ограничения страниц; не нужно заменять это
  без измерений «оптимизацией», создающей ещё больше запросов.
- Фоновые Google-зависимости отделены flavor-specific source sets; generic проверяется отдельно.

## Проверки

Точные результаты последнего прогона см. [VALIDATION.md](VALIDATION.md).
Основные команды для повторения:

```bash
./gradlew detekt ktlintCheck
./gradlew :app:testGenericDebugUnitTest
./gradlew :app:assembleGenericRelease :app:assembleGplayDebug
python3 scripts/check_fork_backup_policy.py
git diff --check
```

`detekt` может завершаться успешно при наличии известных замечаний: проект содержит baseline/порог.
Успешный exit code не следует интерпретировать как отсутствие технического долга.
Новые замечания в изменённых файлах должны быть устранены либо иметь узкое обоснованное подавление.

## План проверки производительности на устройстве

Сравнивать исходный commit и исправленный APK одного build type, на одном устройстве,
с одинаковыми аккаунтами, сервером, сетью и размером истории. Выполнить не менее пяти повторов.

| Сценарий | Что записать | Ожидаемое свойство |
|---|---|---|
| Самоподписанный сертификат, диалог открыт 10 секунд | CPU TLS threads в Android Studio profiler/Perfetto | Нет непрерывного CPU spin; timeout/accept/cancel сохраняются |
| Загрузка крупного вложения при DEBUG logging | Heap/RSS, GC, время первого байта/завершения | HTTP logger не создаёт копию всего response body |
| Обрыв сети, остановка загрузки, повторный download | Открытые дескрипторы, `.part`, размер и хеш файла | Старый полный файл не заменяется частичным |
| Список 500+ разговоров, burst сообщений | Frame times, ANR, DB/query time, число запросов | Нет регрессии UI; coalescing не нарушен |
| 20 циклов звонок/завершение и смена аккаунта | WebSocket/threads/native heap, microphone/camera indicators | После завершения ресурсы возвращаются к baseline |

Нельзя заключать «стало на X% быстрее» без этих измерений. Эмулятор и unit-тесты не заменяют
проверку батареи, Android Doze, кодеков и Bluetooth на физических устройствах.

## Организация двух PR

Полный PR в `dauge-gpt:main` объединяет изменения по запросу владельца форка,
но коммиты сгруппированы по теме для ревью и отката. Upstream-кандидат содержит только A1 и тесты.
Корпоративная backup-политика, русская документация и прочие независимые исправления туда не включены.
См. [UPSTREAM_RU.md](UPSTREAM_RU.md) перед отправкой в Nextcloud.
