# Подпись APK

Release и debug APK подписываются **одним и тем же ключом** (схемы V1 + V2 + V3), поэтому сборку можно ставить поверх предыдущей.
Ключ никогда не лежит в репозитории и не попадает в артефакты.

Откуда берётся ключ (шаг «Prepare signing key» в `.github/workflows/build.yml`, итог — в *Summary* запуска):

| Режим | Когда | Что делать |
|---|---|---|
| `secrets` | в репозитории заданы Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | ничего; ключ постоянный |
| `auto-new` | Secrets нет, кэша ключа ещё нет | ничего; ключ создаётся (RSA 2048, PKCS12, 30 лет) и кладётся в Actions cache |
| `auto-cached` | Secrets нет, кэш найден | ничего; подпись та же, что в прошлой сборке |

## Ограничения авто-ключа
* Кэш GitHub удаляется, если к нему не обращались 7 дней. Тогда следующая сборка создаст **новый** ключ (в Summary будет `auto-new`),
  и старое приложение нужно один раз удалить перед установкой (Android не ставит APK с другой подписью поверх).
* Для pull request из форков кэш не сохраняется (ключ живёт только в этом запуске).
* Кэш доступен только запускам этого репозитория; для приватного репозитория этого достаточно. Если нужен ключ, который нельзя потерять
  и который не зависит от кэша — используйте постоянный (ниже).

## Постоянный ключ (один раз, 2 минуты)
1. На телефоне/ПК с JDK (Termux: `pkg install openjdk-17`) в корне проекта: `bash tools/make_keystore.sh`.
2. Скрипт создаёт `signing/release.jks` и `signing/github-secrets.txt` (4 строки `ИМЯ=значение`; обе папки в `.gitignore`).
3. GitHub → репозиторий → *Settings* → *Secrets and variables* → *Actions* → *New repository secret*: создать 4 секрета —
   имя = текст до `=`, значение = всё после `=`.
4. Удалить `signing/github-secrets.txt`, а `signing/release.jks` **сохранить в надёжном месте**: потеряете — сменится подпись.

Ключ от Android Studio (`.jks`) тоже подходит: `base64 -w0 my.jks` → `KEYSTORE_BASE64`, плюс пароль хранилища, alias и пароль ключа.

## Проверка
В логе шага «Verify APK signatures» для каждого APK печатаются `Verifies` и SHA-256 сертификата; шаг падает, если APK не подписан.
Вручную: `apksigner verify --print-certs app-release.apk`.

## Пакеты
* release: `app.codeedit`; debug: `app.codeedit.debug` (ставится рядом с release).
* `versionCode` = номер запуска workflow, `versionName` = `1.0.1`.
