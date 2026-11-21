# AGENTS.md — Инструкция для ИИ-агентов (Antigravity, Claude, etc.)

## О проекте
- **Название проекта**: Panel Log 3030 (G1 Hotelo / G1 Hotelo Pro)
- **Тип**: Мобильное Android-приложение
- **Основной стек**: Kotlin, Android SDK 34 (minSdk 23), Gradle 8.9, Temurin JDK 17
- **Назначение**: Приём и разбор графического вывода пожарной панели Notifier NFS2-3030 через порт принтера TB5 (PTX/REF) по USB-RS232 (FT232R, 9600 8N1), визуализация логов, формирование отчётов (PDF / WhatsApp).
- **Флейворы**:
  - `simple`: "G1 Hotelo" — базовый функционал (com.panellog.app)
  - `pro`: "G1 Hotelo Pro" — объекты, история, сравнение (com.panellog.app.pro)

## Автосборка и версионирование
- Переменная окружения `GITHUB_RUN_NUMBER` передаётся в `app/build.gradle.kts` для установки `versionCode` и `versionName`. Благодаря этому каждая новая сборка имеет строго больший `versionCode` и корректно устанавливается на телефон поверх предыдущей.
- Все сборки подписываются постоянным ключом `app/panellog.keystore`, чтобы не требовалось удалять приложение при установке обновлений.
- Workflow `.github/workflows/build-apk.yml` автоматически собирает `app-debug.apk` и публикует его в постоянный релиз с тегом `latest`.
- **Постоянная ссылка на скачивание APK**:
  `https://github.com/israbars/panel-log-3030/releases/download/latest/app-debug.apk`

---

## ОБЯЗАТЕЛЬНОЕ ПРАВИЛО ПОСЛЕ ЛЮБЫХ ПРАВОК

После внесения любых изменений в код, конфигурации или документацию агент **ОБЯЗАН** выполнить следующий алгоритм:

1. **Зафиксировать и отправить изменения:**
   ```bash
   git add .
   git commit -m "<Понятное описание сделанных изменений>"
   git push origin main
   ```

2. **Дождаться завершения сборки:**
   ```bash
   gh run watch
   ```

3. **Если сборка упала (failed):**
   - Просмотреть причину падения:
     ```bash
     gh run view --log-failed
     ```
   - Исправить ошибку, снова сделать commit/push и дождаться успешного завершения.

4. **В конце ответа пользователю:**
   - Обязательно приложить прямую ссылку на скачивание актуального APK:
     [Скачать app-debug.apk (latest)](https://github.com/israbars/panel-log-3030/releases/download/latest/app-debug.apk)

---

## Работа с сервером и Remote Control
- Рабочая директория проекта: `/home/bars/panel-log-3030`
- Для долгоживущих процессов и управления агентом удалённо через веб-интерфейс antigravity.google.com используется команда:
  ```bash
  tmux new-session -d -s agy "cd /home/bars/panel-log-3030 && agy remote-control"
  ```
  Для просмотра вывода сессии: `tmux attach -t agy`
