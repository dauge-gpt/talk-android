<!--
SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# Как отправить подготовленное исправление в Nextcloud

## Какие PR подготовлены

1. `codex/android-audit` → `dauge-gpt/talk-android:main`: полный набор изменений для форка.
2. `codex/tls-certificate-wait` → `dauge-gpt/talk-android:master`: небольшой review PR,
   в котором только устранение активного ожидания TLS-сертификата и регрессионные тесты.

Второй PR — материал для проверки внутри форка. Он не отправлен в Nextcloud.
Его base `master` соответствует исходной ветке upstream на момент подготовки.
Не надо сливать этот review PR отдельно, если полный PR уже принят: одинаковое исправление входит в оба.
Впоследствии review PR можно закрыть, сохранив ветку для upstream.

## Почему последний шаг выполняет человек

`AGENTS.md` и [AI Contribution Policy](https://github.com/nextcloud/.github/blob/master/AI_POLICY.md)
прямо требуют:

- человек читает, проверяет и понимает весь отправляемый diff;
- описание PR и ответы maintainers пишет сам участник, своими словами;
- DCO `Signed-off-by` добавляет только человек после ознакомления с сертификатом;
- AI assistance раскрывается в PR и через `Assisted-by` в каждом соответствующем commit;
- агент не отправляет PR/security report автономно.

Созданные в собственном форке review PR оформлены по прямому запросу его владельца.
Их автоматическое описание нельзя копировать в Nextcloud как якобы самостоятельно написанный текст.
В подготовленных commits намеренно нет DCO-подписи пользователя.
Трейлер `Assisted-by: Codex:GPT` указывает доступную в сессии идентификацию инструмента;
точный model-version среда не раскрыла. Перед upstream уточните требуемую maintainers форму disclosure,
не приписывайте неизвестную версию модели.

## 1. Получить и прочитать изменения

```bash
git fetch origin
git fetch upstream
git switch -c upstream/tls-certificate-wait origin/codex/tls-certificate-wait
git log --oneline upstream/master..HEAD
git diff upstream/master...HEAD -- \
  app/src/main/java/com/nextcloud/talk/utils/ssl/TrustManager.java \
  app/src/test/java/com/nextcloud/talk/utils/ssl/TrustManagerTest.kt
```

Если upstream ушёл вперёд, выполните `git rebase upstream/master`, разрешите конфликты и повторите тесты.
Это отдельная ветка, поэтому история `main` форка не переписывается.
До отправки посмотрите, нет ли уже аналогичного исправления в open/merged PR upstream.

## 2. Что нужно уметь объяснить

- Чем `wait` отличается от busy loop и почему ожидание отпускает монитор.
- Зачем снова проверять сертификат после notify/spurious wakeup.
- Почему notifyAll для другого сертификата не означает доверие текущему.
- Почему используется System.nanoTime, а не wall clock.
- Как завершается timeout и почему interrupt приводит к отказу с сохранённым interrupt flag.
- Где синхронизируются чтение и запись KeyStore; что остаётся от прежней политики доверия.

Для ручной проверки используйте тестовый сервер с новым самоподписанным сертификатом:
вход/accept, отказ/timeout, несколько одновременных запросов, прерывание соединения.
Не используйте продуктивные аккаунты для fault injection.

```bash
./gradlew detekt ktlintCheck
./gradlew :app:testGenericDebugUnitTest \
  --tests 'com.nextcloud.talk.utils.ssl.TrustManagerTest'
./gradlew :app:assembleGenericDebug
```

Сохраните фактический результат и версию Android; не утверждайте, что manual checks выполнены,
если проверены только unit-тесты.

## 3. Добавить свою DCO-подпись

Прочитайте `contribute/developer-certificate-of-origin` и убедитесь, что можете его подтвердить.
В своей локальной upstream-ветке укажите действительное имя и email:

```bash
git config user.name 'ВАШЕ ПОЛНОЕ ИМЯ'
git config user.email 'ВАШ EMAIL'
git commit --amend --no-edit --reset-author --signoff
git log -1 --format=full
```

Команда относится к одному подготовленному commit. Если после rebase/доработок commits несколько,
проверьте DCO у каждого. Сохраните Assisted-by. Изменения, произведённые с AI, не становятся
«написанными без AI» после смены автора или squash.

## 4. Опубликовать новую ветку и создать upstream PR

```bash
git push -u origin upstream/tls-certificate-wait
```

На GitHub откройте `nextcloud/talk-android` → Pull requests → New pull request → compare across forks:

| Поле | Значение |
|---|---|
| base repository | `nextcloud/talk-android` |
| base | `master` |
| head repository | `dauge-gpt/talk-android` |
| compare | `upstream/tls-certificate-wait` |

Проверьте, что diff содержит только TrustManager и тест, без документации/backup/logging изменений.
Напишите описание самостоятельно: наблюдаемое поведение, причина, семантика исправления,
фактические проверки и использование Codex. Не прикладывайте реальные токены, URL приватного сервера
или дампы переписки. Перед запросом review снимите draft только после завершения проверок.

Не нужно давать агенту доступ к организации Nextcloud, переносить форк или переписывать их master.
PR предлагает изменения; принять их могут maintainers. Они могут потребовать другую реализацию,
дополнительные тесты или отказать в принятии. Исправления продолжат работать в нашем форке независимо от решения.
