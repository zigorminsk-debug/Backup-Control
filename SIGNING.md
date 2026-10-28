# Постоянный ключ подписи

Android устанавливает обновление поверх установленного приложения **только** если оно
подписано тем же ключом. Если ключ меняется, обновление отклоняется («Приложение не
установлено»), и его можно поставить лишь после удаления приложения — вместе со всеми
проверками. Поэтому ключ подписи должен быть один и тот же навсегда.

| Параметр | Значение |
| --- | --- |
| Отпечаток сертификата SHA-256 | `6ceb4c2eeb3f3d5a22ea325fb68923c713bd8ac012a3706c4a8ce6369183d032` |
| Псевдоним (alias) | `backup-control` |
| Формат | PKCS12, RSA 2048, срок действия до 2056 года |
| Публичный сертификат | [`docs/release-certificate.crt`](docs/release-certificate.crt) |
| Закреплённый отпечаток | [`docs/release-fingerprint.txt`](docs/release-fingerprint.txt) |

Сам файл ключа и пароли **не хранятся в репозитории** (`.gitignore` исключает `signing/`
и `*.p12`). Они передаются в сборку через секреты GitHub Actions.

## Как включить ключ в сборку

`Settings → Secrets and variables → Actions → New repository secret` — добавить четыре секрета:

| Секрет | Значение |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | файл `backup-control-release.p12` в base64 одной строкой |
| `ANDROID_KEYSTORE_PASSWORD` | пароль хранилища |
| `ANDROID_KEY_ALIAS` | `backup-control` |
| `ANDROID_KEY_PASSWORD` | пароль ключа (совпадает с паролем хранилища) |

То же самое из терминала:

```bash
base64 -w0 backup-control-release.p12 | gh secret set ANDROID_KEYSTORE_BASE64 --repo zigorminsk-debug/Backup-Control
gh secret set ANDROID_KEYSTORE_PASSWORD --repo zigorminsk-debug/Backup-Control
gh secret set ANDROID_KEY_ALIAS --repo zigorminsk-debug/Backup-Control --body backup-control
gh secret set ANDROID_KEY_PASSWORD --repo zigorminsk-debug/Backup-Control
```

Пока секретов нет, workflow собирает только **debug**-версию с одноразовым ключом раннера и
честно предупреждает об этом: ставить её поверх прошлых сборок нельзя, данные не сохранятся.

## Что проверяет CI

1. `Прочный ключ подписи` — job `android` печатает отпечаток сертификата в Summary запуска.
2. `Verify signature and signing-key continuity` — перед публикацией релиза CI сверяет
   отпечаток сертификата собранного APK с закреплённым в `docs/release-fingerprint.txt`.
   Несовпадение останавливает публикацию, поэтому смена ключа не может случиться случайно.
3. `Publish GitHub Release` — дополнительно проверяет, что `versionCode` APK совпадает с
   тегом `v1.0.<run>`, из которого приложение-обновлятор берёт номер версии.

## Если ключ всё-таки потерян

Обновление поверх установленного приложения станет невозможным: пользователю придётся
удалить приложение, а вместе с ним пропадёт чек-лист и архив. Держите копию файла ключа и
пароля в менеджере секретов (KeePass/Bitwarden) и в офлайн-бэкапе.

## Осознанная ротация ключа

1. Сгенерировать новый PKCS12, обновить четыре секрета.
2. В одном коммите заменить `docs/release-certificate.crt` и `docs/release-fingerprint.txt`.
3. Понимать последствие: для всех, кто уже установил приложение, обновление потребует
   переустановки с потерей локальных данных.
