# UniDocs Assistant

[![CI](https://github.com/Nurasyldsdgs/intelligent-university-document-assistant/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Nurasyldsdgs/intelligent-university-document-assistant/actions/workflows/ci.yml)

Course document vault with AI classification, deadline extraction and Q&A.
Backend: Spring Boot 3 (Java 21) + PostgreSQL. Frontend: React + Vite.

## Run locally

Prerequisites: Docker, JDK 21, Node.js 20+. Maven does not need to be installed: the Maven Wrapper
(`backend/mvnw`, `backend/mvnw.cmd`) downloads Maven 3.9.9 into `~/.m2/wrapper` on first run.

```bash
# 1. Database (PostgreSQL 16 on localhost:5432)
docker-compose up -d

# 2. Backend config: secrets come from backend/.env, never from committed files
cp backend/src/main/resources/application.example.yml backend/src/main/resources/application.yml
cp backend/.env.example backend/.env
#    then edit backend/.env: AI_API_KEY (Groq), MAIL_*, GOOGLE_* as needed

# 3. Backend on http://localhost:8080 (run from backend/ so backend/.env is picked up)
cd backend
./mvnw spring-boot:run          # macOS / Linux / Git Bash
.\mvnw.cmd spring-boot:run      # Windows PowerShell or cmd

# 4. Frontend on http://localhost:5173 (in a second terminal)
cd frontend
npm install
npm run dev
```

Uploaded files are stored in `backend/uploads/` (set `UPLOAD_DIR` to change it); this folder is git-ignored.
Without an AI key the app still works, just without classification, deadline extraction and Q&A.

## OCR (распознавание сканов)

Сканы PDF и изображения без текстового слоя распознаются Tesseract в фоне после загрузки (не в запросе загрузки).
По умолчанию OCR выключен; без него сканы просто остаются без текста.

Установка на Windows:

1. Скачайте установщик сборки UB Mannheim: <https://github.com/UB-Mannheim/tesseract/wiki>
   (`tesseract-ocr-w64-setup-5.x.x.exe`).
2. В установщике на шаге «Choose Components» раскройте **Additional language data (download)** и отметьте
   **Russian** и **Kazakh** (английский ставится всегда). Путь оставьте `C:\Program Files\Tesseract-OCR`.
3. Если языки не были выбраны, скачайте `rus.traineddata` и `kaz.traineddata` из
   <https://github.com/tesseract-ocr/tessdata> в папку `C:\Program Files\Tesseract-OCR\tessdata`.
4. Проверьте в PowerShell — в списке должны быть `eng`, `kaz`, `rus`:

   ```powershell
   & "C:\Program Files\Tesseract-OCR\tesseract.exe" --list-langs
   ```

5. Добавьте в `backend/.env` и перезапустите backend:

   ```dotenv
   OCR_ENABLED=true
   OCR_TESSERACT_PATH=C:\Program Files\Tesseract-OCR
   ```

При запуске в логе появится `OCR ready: Tesseract with rus+kaz+eng …`. Если Tesseract или язык не найден — одно
предупреждение `OCR is enabled but cannot run … Missing: …`, и приложение работает без OCR.

Необязательные настройки (`backend/.env.example`): `OCR_LANGUAGES` (по умолчанию `rus+kaz+eng`), `OCR_TIMEOUT`
(`120s` на документ), `OCR_MAX_PAGES` (распознаются первые 20 страниц). Проверка на настоящем Tesseract:
`.\mvnw.cmd test -Dtest=TikaOcrEngineSmokeTest -Docr.tesseract.path=C:/PROGRA~1/Tesseract-OCR` (короткий путь без
пробелов — Maven Wrapper не принимает путь с пробелом в кавычках).

## Тесты

Обычный прогон не требует базы данных. Тесты на PostgreSQL и настоящем Tesseract в нём пропускаются (skipped):

```powershell
cd backend
.\mvnw.cmd test
cd ..\frontend
npm run lint
npm run build
```

### Тесты на PostgreSQL

`*PostgresTest` проверяют на настоящем PostgreSQL 16 native SQL: выдачу заданий (`fanOut`), синхронизацию задач,
поиск по `text_tsv` (по `masked_text`), фильтры видимости для студентов и миграции схемы. Они запускаются только с
`-Dit.db.url` и работают в **отдельной** базе `doc_assistant_test` в контейнере docker-compose.

> **Никогда не указывайте `doc_assistant_db`.** Тесты создают схему заново и удаляют её после прогона
> (`ddl-auto=create-drop`): все таблицы рабочей базы были бы удалены.

Из корня репозитория в PowerShell:

```powershell
# 1. База docker-compose (контейнер doc_assistant_postgres, порт 5432)
docker-compose up -d

# 2. Отдельная тестовая база, один раз; doc_assistant_db не затрагивается
if (-not (docker exec doc_assistant_postgres psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'doc_assistant_test'")) { docker exec doc_assistant_postgres psql -U postgres -c "CREATE DATABASE doc_assistant_test" }

# 3. Все тесты на PostgreSQL
cd backend
.\mvnw.cmd test "-Dtest=*PostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.db.url=jdbc:postgresql://localhost:5432/doc_assistant_test" "-Dit.db.user=postgres" "-Dit.db.password=postgres_password"
```

Пароль `postgres_password` — тот же, что в `docker-compose.yml`, для локального контейнера.

**Если порт 5432 занят** локально установленным PostgreSQL, контейнер docker-compose на нём не запустится. Тогда
поднимите тот же сервис на порту 5433. Обычный контейнер `doc_assistant_postgres` в это время должен быть остановлен:
оба используют один том с данными.

```powershell
docker-compose run -d --rm -p 5433:5432 --name doc_assistant_postgres_it postgres
# шаг 2 с doc_assistant_postgres_it вместо doc_assistant_postgres, шаг 3 с localhost:5433
docker stop doc_assistant_postgres_it   # после прогона
```

### CI

GitHub Actions (`.github/workflows/ci.yml`) проверяет каждый push и pull request в `main` и `feature/**`: все тесты
backend вместе с тестами на PostgreSQL (сервис-контейнер PostgreSQL 16 с базой `doc_assistant_test`), затем
`npm ci`, `npm run lint` и `npm run build` во frontend. Секреты не нужны: ИИ, вход через Google, почта, OCR и S3
выключены. Результат — во вкладке **Actions** репозитория и в значке CI вверху README; при падении тестов отчёты
Surefire прикладываются к запуску (артефакт `surefire-reports`).
