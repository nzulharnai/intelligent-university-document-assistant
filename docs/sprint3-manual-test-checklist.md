# Sprint 3 — manual browser test checklist

Three accounts, one course:

| Account | Role | Relation to INF345 |
|---|---|---|
| **T** | TEACHER | teacher of INF345 |
| **S** | STUDENT | enrolled in INF345 |
| **D** | ADMIN (dean's office) | — |

Use separate browser profiles (or one normal and one private window) so two accounts can be logged in at once.
Automated coverage of the same cases: `Sprint3QaTest` (QA-AI-008, QA-SEC-009, QA-OCR-007). This list checks what a
unit test cannot: the real Groq model, the real Tesseract, PostgreSQL and the browser.

## Prerequisites

- [ ] `docker-compose up -d`; backend started with `.\mvnw.cmd spring-boot:run` from `backend/`, frontend with
      `npm run dev`. Start against a database that **already has Sprint 2 data** at least once, to check the migrations.
- [ ] `backend/.env` has a real Groq key, `OCR_ENABLED=true` and `OCR_TESSERACT_PATH=C:\Program Files\Tesseract-OCR`
      (Tesseract installed as in the README section "OCR (распознавание сканов)").
- [ ] Backend log at startup has no errors and shows:
  - `OCR ready: Tesseract with rus+kaz+eng …`;
  - the masking backfill line (number of documents masked, `0` on the second start);
  - `Full-text search columns and indexes on documents and document_chunks are in place`.
- [ ] Test files ready:
  - `syllabus.pdf` with text: 2–3 labs with dates, plus a student line such as
    "Староста: Иванов А., ИИН 990101300013, тел. +7 701 123 45 67, ivanov@mail.ru";
  - `scan.pdf`: the same syllabus printed and scanned (or a PDF of page images, no text layer);
  - `lab1.docx`: "Лабораторная работа №1" with the same deadline as lab 1 of the syllabus;
  - a multi-page scan for the OCR failure check.

## Teacher of INF345 (T)

**AI drafts (US-08)**
- [ ] Upload `syllabus.pdf` with "Определить автоматически (ИИ)". The upload notice reports drafts for review, not
      published deadlines.
- [ ] The course page shows "Черновики от ИИ (N)" with the labs, their deadlines and requirements. "Задания курса" does
      not list them yet.
- [ ] "Изменить" on a draft: change the title and the deadline, save. "Черновик сохранён"; the draft shows the new values.
- [ ] Clear the deadline of a draft and click "Опубликовать": a confirmation warns "Нет срока сдачи…".
- [ ] "Удалить черновик" asks for confirmation and removes it.
- [ ] "Опубликовать все": the confirmation says "Их получат все студенты курса."; afterwards the drafts move to
      "Задания курса" and the draft block disappears.

**Editing a published assignment**
- [ ] After S has set lab 1 to "В работе" (see below), "Изменить" lab 1 and move its deadline by a week. The toast says
      the students see the change.
- [ ] D's audit log shows the deadline change with the old and the new date.
- [ ] Ask the AI to analyze the syllabus again (document page, "Анализ ИИ и задачи"). Lab 1 keeps the teacher's title
      and deadline; no second "Лабораторная работа 1" appears.
- [ ] "Закрыть задание" on lab 2: it stays in the list as "Закрыто". "Открыть снова" makes it active again.
- [ ] Delete a published assignment S has worked on: it is refused with "close it instead". Close it instead.

**Duplicates**
- [ ] Upload `lab1.docx` (automatic type). Its draft shows "Похоже на: Лабораторная работа 1 …" with
      "Это дубль — удалить черновик" and "Это другое задание".
- [ ] "Это другое задание" removes the mark ("Отметка «похоже на» снята"). Publishing a still-marked draft asks
      "Опубликовать возможный дубль?".

**Personal data (US-09)**
- [ ] `syllabus.pdf` shows the badge "Личные данные: 3"; hovering lists the kinds.
- [ ] Tab "Текст" shows the **original** text, with the IIN, phone and e-mail.
- [ ] Opening the "Просмотр" tab and downloading the file both appear in D's audit log as openings of an original with
      personal data.
- [ ] "Разрешить студентам оригинал" asks for confirmation; switch it on, check as S (below), then switch it off.

**OCR (US-07)**
- [ ] Upload `scan.pdf` (automatic type). The row and the course list show "Распознаём скан…", then the category
      appears without a reload, with "Распознано со скана". For a syllabus, drafts appear in "Черновики от ИИ".
- [ ] Tab "Текст" shows the recognized text (Russian and Kazakh letters readable).
- [ ] Set `OCR_TIMEOUT=1s`, restart, upload the multi-page scan. The document stays in the course with
      "Нужна проверка"; its page shows the panel "Нужна проверка: текст скана распознать не удалось."
- [ ] "Указать тип вручную" → "Силлабус" → "Сохранить": the badge changes and the panel's type follows; D's log shows
      "Тип документа указан вручную".
- [ ] Remove `OCR_TIMEOUT`, restart, open another failed scan, "Повторить распознавание": the panel disappears,
      "Распознаём скан…" runs, then the result appears. D's log shows "Повторное распознавание скана".

## Student of INF345 (S)

- [ ] While T's uploads are being classified or recognized ("Распознаём скан…" for T), S does not see them in the
      course list, in "Хранилище документов" or in search.
- [ ] Before T publishes: S sees no drafts and has no new tasks in "Мои задачи". After "Опубликовать все": each lab is
      in "Мои задачи".
- [ ] Set lab 1 to "В работе" (and hand in a file if available). After T moves the deadline, the task shows the new
      deadline and is still "В работе" with the file.
- [ ] Open `syllabus.pdf`: tab "Текст" shows "[ИИН скрыт]", "[телефон скрыт]", "[e-mail скрыт]" and
      "Часть личных данных скрыта."; T's e-mail is readable.
- [ ] "Просмотр" and the download button show "Оригинал доступен только преподавателю" instead of the file. Calling
      `GET /api/v1/documents/{id}/download` directly gives 403 with the restricted-original message.
- [ ] After T allowed the original: the file opens and downloads; the "Текст" tab is still masked. After T switched it
      off: 403 again.
- [ ] Search for `990101300013` and for `701 123 45 67`: no results. Search for "Алгоритмы": the syllabus is found and
      its snippet has no personal data.
- [ ] Chat with AI on the syllabus: "Какой ИИН у старосты?" → the answer says the data is hidden, never a number.
      Course chat: same question, same result.
- [ ] As S, `POST /api/v1/documents/{id}/ocr/retry`, `POST /api/v1/documents/{id}/category`,
      `POST /api/v1/assignments/{id}/publish` and `PATCH /api/v1/assignments/{id}` all answer 403.

## Review fixes: unchecked scans and failed documents

- [ ] With `OCR_ENABLED=false`, T uploads `scan.pdf` with the type "Лекция". T sees "Скан не проверен на личные
      данные". S sees the document, but "Просмотр" shows "Скан ещё не проверен на личные данные" and
      `GET /api/v1/documents/{id}/download` answers 403 `PII_ORIGINAL_RESTRICTED`.
- [ ] T switches on "Разрешить студентам оригинал": S opens the file, and D's log shows the opening.
- [ ] With OCR on, the same scan after "Распознано со скана" without personal data opens for S without permission.
- [ ] Set `AI_ENABLED=false`, restart, and upload a file with "Определить автоматически". The upload
      result says "ИИ недоступен — укажите тип, чтобы студенты увидели файл". The course page shows
      "Требуют проверки: 1", and clicking it lists only that file. S does not see it in the list, in search, in course
      chat or by id (403).
- [ ] T opens it and uses "Указать тип вручную" → "Лекция": the counter goes down and S sees the document.
- [ ] A scan whose OCR failed ("Нужна проверка") is hidden from S in the same way until its type is set by hand.

## Review fix: long syllabi

- [ ] T uploads a syllabus of 20+ pages with labs throughout (automatic type). The backend log shows several
      calls for that document, each at most `max-input-chars` (15000) long, possibly with 429 retries in between; the
      drafts include labs from the last pages, each lab once.
- [ ] With a deliberately low limit (e.g. `AI_MAX_INPUT_CHARS=3000`) and `AI_MAX_ATTEMPTS=1`, re-analyze it. A chunk
      may end with 429 and a log line "chunk N of M got no usable answer". The drafts of the other chunks are kept,
      and "Черновики от ИИ" shows "ИИ обработал документ не полностью — проверьте задания вручную" with a link to the
      document. Published labs of that document are not closed.
- [ ] Restore the settings and re-analyze: the notice disappears.

## Review fix: encrypted storage

- [ ] With no key, start once and upload a file: the log warns "NOT encrypted at rest"; the file in `uploads/` is
      the plain file.
- [ ] Generate a key with the PowerShell line from `.env.example`, put it in `backend/.env` as
      `STORAGE_ENCRYPTION_KEY=…`, restart. The log shows "encrypted at rest (AES-256-GCM)" and
      "N old plain file(s) encrypted". A second restart logs "no plain files left to encrypt".
- [ ] Every file in `uploads/` now starts with `UDENC`. Old and new documents open in "Просмотр" and download with
      the right size, and a student's submitted file downloads for the teacher.
- [ ] `STORAGE_ENCRYPTION_ENABLED=true` with the key line removed: the backend refuses to start and names
      STORAGE_ENCRYPTION_KEY, without printing any key.

## Review fix: small privacy issues

- [ ] T opens "Профиль" in the user menu and sets the phone `+7 702 555 12 34`. The dialog closes with "Профиль
      сохранён", and D's log shows "Изменение профиля" without the number. An invalid phone (`12-34`) shows the
      PHONE_INVALID text.
- [ ] Shortly after, in a syllabus of INF345 that lists T's phone and a student's phone, S sees T's phone and e-mail
      and "[телефон скрыт]" for the student's. The backend log shows "Re-masked N document(s) of course(s) [..]".
- [ ] D assigns INF345 to another teacher: the old teacher's phone is now masked for S in that syllabus.
- [ ] A text with "ИИН 990101 300013" or "9901-0130-0013" shows "[ИИН скрыт]" for S. "Приказ № 9901 0130 0013" and
      "2024 2025 2026" stay as they are.
- [ ] Upload a file named `Иванов_990101300013.pdf`. S sees `Иванов_[ИИН скрыт].pdf` in the course list, search,
      document page, tasks and course chat sources. After "Разрешить студентам оригинал", the downloaded file has
      that masked name too. T and D see the real name everywhere.
- [ ] As S, ask the course chat "Мой ИИН 990101300013 — где расписание?". The answer comes, and the backend never
      sends the number (debug log of the AI request, or a proxy).

## Review fix: Google sign-in

- [ ] With `GOOGLE_CLIENT_ID` empty or a placeholder: the backend starts, logs one warning naming the missing
      variable (no value), and the login page shows no Google button. Password login works.
- [ ] With a real client: the button appears. Cancel on Google's page: back on the login page with "Вход через Google
      отменён". A successful sign-in lands in the app.

## Review fix: final review, student paths

As S, with T's course holding: a TEACHER_ONLY lecture, an `EXAM_ANSWERS` upload, a file still "Распознаём скан…",
a file uploaded with `AI_ENABLED=false` ("Требуют проверки"), a failed scan, an unpublished draft, a syllabus with
personal data and `Иванов_990101300013.pdf`.
- [ ] Course list and course page: the course shows; no student list, no "Требуют проверки", no drafts block.
- [ ] Document list of the course, "Хранилище документов" and search (`Лекция`, a word of the exam answers, the file
      names): none of the hidden files appear; `Иванов_[ИИН скрыт].pdf` is the only form of that name.
- [ ] Each hidden file by id: `GET /api/v1/documents/{id}`, `/text`, `/analysis`, `/view`, `/download`, `/status`,
      `/assignments` and `POST /api/v1/documents/{id}/ask` all answer 403. `GET /api/v1/documents/status?ids=…` with
      their ids returns an empty list.
- [ ] The syllabus: "Текст" masked, "Просмотр" and download 403 `PII_ORIGINAL_RESTRICTED`, document chat answers
      with placeholders only.
- [ ] Course chat, a question about the exam answers or the hidden lecture: the answer cites none of them.
- [ ] `GET /api/v1/courses/{id}/assignments/drafts` answers 403; "Задания курса" and "Мои задачи" list only published
      assignments.

## Review fix: CI

- [ ] After pushing to `main` or a `feature/…` branch, the repository's **Actions** tab shows a green "CI" run with
      the jobs "Backend (Java 21, PostgreSQL 16)" and "Frontend (Node 20)". The backend log lists
      `AssignmentLifecyclePostgresTest` with 16 tests run, none skipped. The README badge is green.

## Dean's office (D, ADMIN)

- [ ] D sees the drafts of INF345 and can publish, edit, close and reopen like T.
- [ ] D's "Текст" tab shows the original; opening or downloading the original is audited under D's own e-mail.
- [ ] D can use "Указать тип вручную" and "Повторить распознавание" on a failed scan.
- [ ] The audit log on the admin page shows Russian labels, with no raw codes, for: publishing, editing a deadline, closing,
      reopening, opening an original with personal data, allowing / denying the original, setting a type by hand,
      retrying recognition, and refused access.
- [ ] Turn `PRIVACY_PUBLIC_EMAIL_DOMAINS` to your university's domain, restart: the backfill re-masks the documents
      once (log), and e-mails on that domain become readable for S.

## Upgrade of an existing database

- [ ] After the first start on Sprint 2 data: old assignments keep their status (`ACTIVE` / `CLOSED`), old documents
      have masked text and the "Текст" tab works for S, search still finds old documents by ordinary words.
- [ ] A second start logs nothing to backfill and no schema rebuild.
- [ ] On a database from before the review fixes (a copy of yours, never the only one): the first start masks the
      texts again ("Masked the texts of N document(s)"; IINs in groups now hidden for S) and, with a key, encrypts the
      old files ("N old plain file(s) encrypted"). Old documents open and download as before; tasks keep their
      status. The second start reports nothing to do.
