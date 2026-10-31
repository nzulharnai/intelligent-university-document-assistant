# Changelog

## Sprint 4 — Submissions, grading, tracking, reminders

US-10 Submit Assignment Work, US-11 Grade and Give Feedback, US-12 Track My Assignments, US-13 Deadline Reminders.
Manual browser checks: [docs/sprint4-manual-test-checklist.md](docs/sprint4-manual-test-checklist.md). The
acceptance cases QA-SUB-010, QA-GRD-011, QA-UI-012 and QA-NOT-013 are `Sprint4QaPostgresTest`: the real services
on PostgreSQL with real commits, a fixed clock and mocked mail.

### US-10 — Submitting work

#### Honest submission lifecycle

- **Task statuses.** An assignment task is "Не начато" (PENDING) or "В работе" (IN_PROGRESS), which the student
  sets, then "Сдано" (SUBMITTED) and "Оценено" (GRADED), which only the system sets. A submission makes the task
  SUBMITTED, stores `tasks.submitted_at` (the latest submission) and `tasks.late` (after the deadline), in one
  transaction with the submission rows. A student who sets SUBMITTED, GRADED or COMPLETED by hand, or moves a
  submitted task back, gets 400 `TASK_STATUS_SYSTEM_ONLY`. COMPLETED is left only for legacy tasks without an
  assignment, which keep their free changes. "Ближайшие дедлайны" skip submitted and graded tasks.
- **Moved deadline.** When the deadline changes, `late` is computed again, so an extension removes the late mark.
- **Assignments** get `allow_late` (default true) and `max_score` (1..1000, optional) through
  `PATCH /assignments/{id}` (`allowLate: null` keeps the current value). Tasks get `score`, `feedback`, `graded_at`
  and `graded_by_id` for US-11. `TaskResponse` returns `submittedAt, late, score, maxScore, feedback, gradedAt,
  allowLate`, only to the task's own student.
- **Shared clock.** `ClockConfig` provides one `Clock` in `app.time-zone` (`APP_TIME_ZONE`, default `Asia/Almaty`).
  Submission times and the upcoming list use it.
- **Startup migration** `TaskSubmissionStatusMigration` (idempotent):
  - rebuilds `tasks_status_check`;
  - assignment tasks with a submission → SUBMITTED, with `submitted_at` and `late`;
  - assignment tasks marked COMPLETED without a submission → IN_PROGRESS;
  - legacy tasks stay as they are.
- **Access.** The "own task" check moved to `AccessControlService.authorizeOwnTask`, so denials are now audited.

#### Submission rules and the student UI

- **When a submission is accepted.**
  - The assignment must be ACTIVE (409 `ASSIGNMENT_CLOSED`, as before).
  - The task must not be GRADED (400 `SUBMISSION_ALREADY_GRADED`).
  - After the deadline, only if `allow_late`; otherwise 400 `SUBMISSION_DEADLINE_PASSED`, "Срок сдачи истёк ДД.ММ.ГГГГ ЧЧ:ММ…".

  Late means strictly after the deadline by the shared clock: 23:59:59 at a 23:59:59 deadline is on time. The checks
  run before any file is read and again under a row lock on the task (`SELECT … FOR UPDATE`), so concurrent hand-ins
  and a grade given at the same moment are handled correctly.
- **Versions.** Each hand-in (one request with all its files) is a new `task_submissions.version`. Earlier versions
  stay as history and are never deleted. `SubmissionResponse` has `version` and `current`. Rows from before
  versions existed are version 1 (column default).
- **Audit** `TASK_SUBMITTED`: "version N, K file(s) … , late".
- **Student UI.**
  - Status chip "Сдано с опозданием".
  - Button "Сдать" / "Сдать заново".
  - After the deadline a confirmation "Срок прошёл — работа будет отмечена как сданная с опозданием".
  - When the hand-in is not possible (late work forbidden, closed, graded), the button is disabled and the reason is
    shown next to it.
  - History: the current version first, earlier ones collapsed with their times.
- **Teacher.** The assignment edit dialog has "Разрешить сдачу после срока" and "Максимальный балл". The list of
  students' submissions shows the current version and collapses the earlier ones.

### US-11 — Grading and feedback

- **`PUT /tasks/{id}/grade`** `{score, feedback}`: the course teacher or ADMIN (new `Permission.GRADE_WORK`).
  - The score is 0..`max_score`, or 0..1000 when there is none, with at most one decimal; otherwise 400
    `GRADE_SCORE_INVALID`, which names the range. Feedback holds up to 2000 characters.
  - Any assignment task can be graded, including one with nothing handed in (0 for "не сдано"). The task becomes
    GRADED with `graded_at` (shared clock) and `graded_by`.
  - Re-grading is allowed. Legacy tasks give 400 `TASK_NOT_GRADABLE`.
  - The task row is locked, so a hand-in at the same moment is refused as already graded.
- **`DELETE /tasks/{id}/grade`** takes the grade back: SUBMITTED if something was handed in, otherwise PENDING. It
  does nothing for a task that is not graded.
- **Audit:** `TASK_GRADED` ("old -> new") and `TASK_UNGRADED`, with Russian labels in the admin log.
- **`GET /assignments/{id}/grading?state=ALL|NOT_SUBMITTED|SUBMITTED|LATE|GRADED&page&size`** (teacher, ADMIN):
  - the assignment header and the progress counts;
  - one page of enrolled students by name, with state, `submittedAt`, `late`, score, feedback and the files of the
    current version;
  - a fixed number of queries: rows, files of the whole page in one query, counts.
- **`NotificationEvents.taskGraded(taskId)`**, called after commit; empty until US-13.
- **Teacher page `/courses/:courseId/assignments/:id`**, reached by "Оценки" in the course's assignment list:
  - header with the deadline, the max score and whether late work is accepted;
  - "Сдали X из Y · с опозданием Z · оценено W";
  - filters, the list of students, and "Оценить" / "Изменить".
- **Grading dialog:** the current files, the score "из N", feedback, "Сохранить", "Следующий студент" (saves a
  changed grade first) and "Снять оценку" with confirmation.

### US-12 — "Мои задачи" for students

- **`GET /tasks/my`** filters:
  - `state=ALL|TO_DO|OVERDUE|NOT_STARTED|IN_PROGRESS|SUBMITTED|LATE|GRADED` replaces `status`;
  - `courseId`.

  Sorted by deadline (none last), then id; paged. "Просрочено" means nothing handed in and the deadline strictly
  before now by the shared clock. "К сдаче" means nothing handed in, the deadline not passed and the assignment
  still open. Only the current user's own tasks are returned.
- **`GET /tasks/my/summary`** returns `{toDo, overdue, graded}` over the same tasks.
- **"Мои задачи" page.**
  - Summary "К сдаче: N · Просрочено: M · Оценено: K"; each number applies its filter.
  - State chips and a course filter.
  - Empty states "Задач нет" and "По фильтру ничего не найдено" with "Сбросить фильтры". A failed load shows an
    error with "Повторить".
- **Task card.**
  - The course and a relative deadline hint: "Сегодня до 23:59", "Завтра до …", "Через 2 дня · …", "Просрочено на
    1 день · …".
  - Overdue work has a red edge and the "Просрочено" tag with an icon.
  - Graded work shows "8 / 10" and the teacher's feedback, which expands.

### US-13 — Notifications and deadline reminders

#### Notifications in the site and by e-mail

- **Table `notifications`** (user, type, title, body, link, created_at, read_at; deleted with its user) and
  `users.email_notifications` (default true). Types: `ASSIGNMENT_PUBLISHED`, `DEADLINE_CHANGED`,
  `DEADLINE_REMINDER_24H` (step 2), `TASK_GRADED`, `TEACHER_REQUEST_APPROVED`, `TEACHER_REQUEST_REJECTED`.
- **`NotificationService.notify`** writes the rows in the caller's transaction.
  - E-mails go out only after commit, on the new `mailTaskExecutor` (1–2 threads), and only to users who kept
    e-mails on.
  - A failed or refused e-mail is logged (user id, never the address) and does not undo or fail the action.
  - The e-mail is in the site's blue, with "Открыть в UniDocs" (`app.frontend-url` + link) and "Отключить письма
    можно в профиле".
- **Hooks.**
  - Publishing (one or several drafts, a teacher's new assignment, AI auto-publish) → `ASSIGNMENT_PUBLISHED` to
    enrolled students, one per student per action, listing the titles.
  - A moved deadline → `DEADLINE_CHANGED` with the old and the new date.
  - A grade → `TASK_GRADED` with "Оценка: 8.5 из 10". It is now sent inside the grading transaction; the e-mail
    still goes after commit.
  - A teacher-role request approved or rejected → the requester.
- **Endpoints, own notifications only:**
  - `GET /notifications` (newest first, paged);
  - `GET /notifications/unread-count`;
  - `POST /notifications/{id}/read` (404 `NOTIFICATION_NOT_FOUND` for an id that is not the user's);
  - `POST /notifications/read-all`;
  - `PATCH /users/me/preferences {emailNotifications}` (audited as `PROFILE_UPDATED`). `UserResponse` has
    `emailNotifications`.
- **Frontend.**
  - A bell in the navbar with the unread count, polled every 60 s and paused while the tab is hidden.
  - A panel with the latest 10; a click marks one read and opens its link. "Прочитать все".
  - A page `/notifications`.
  - "Присылать уведомления на почту" in "Профиль".

#### Deadline reminders

- **Scheduling** (`SchedulingConfig`, `@EnableScheduling`). The job runs on `app.notifications.reminder-cron`
  (`REMINDER_CRON`), by default every 15 minutes in `app.time-zone`; "-" turns it off.
- **What gets a reminder.** `DeadlineReminderService.sendDue()` sends `DEADLINE_REMINDER_24H` ("Срок сдачи «…» —
  завтра в 23:59" / "сегодня в 18:00") to tasks that match all of:
  - status PENDING or IN_PROGRESS;
  - a deadline within the next `app.notifications.reminder-hours` (`REMINDER_HOURS`, default 24);
  - an ACTIVE assignment;
  - a student still enrolled.
- **Once per task.** `tasks.reminder_sent_at` is claimed with an atomic
  `UPDATE … WHERE reminder_sent_at IS NULL AND <still due>`. Only the caller that gets the row sends, so a second
  run or a second instance sends nothing. A deadline moved later clears the claim, so the new deadline is
  reminded of again; one moved earlier keeps it.
- **Batches** of 100 by id, each in its own short transaction. A one-line log "Deadline reminders: N sent" when N >
  0. E-mails go after each batch commits, through `NotificationService`.
- **`POST /api/v1/admin/notifications/run-reminders`** (ADMIN) runs the job now and returns `{sent}`. It is audited
  as `REMINDERS_RUN` ("Запуск напоминаний о сроках").

### Upgrading an existing database

- **On the first start** `TaskSubmissionStatusMigration`:
  - rebuilds `tasks_status_check`;
  - turns assignment tasks with files into SUBMITTED, with `submitted_at` and `late`;
  - turns assignment tasks marked COMPLETED without files into IN_PROGRESS.

  It is idempotent; legacy tasks keep their status.
- **New columns come with defaults**, so existing rows stay valid:
  - `tasks.late` false;
  - `assignments.allow_late` true;
  - `task_submissions.version` 1 (all earlier files become version 1);
  - `users.email_notifications` true;
  - `tasks.reminder_sent_at`, `score`, `feedback`, `graded_*` and `assignments.max_score` stay null.
- **The first reminder run after the upgrade** reminds about every open task due within 24 hours, once.
- **New settings, all optional:**
  - `APP_TIME_ZONE` (default `Asia/Almaty`);
  - `REMINDER_CRON` (default every 15 minutes, `-` turns it off);
  - `REMINDER_HOURS` (default 24).

### Tests

530 backend tests with PostgreSQL. Without a database the 46 PostgreSQL tests and the real-Tesseract smoke test are
skipped. New for Sprint 4:
- `Sprint4QaPostgresTest`, `TaskSubmissionStatusPostgresTest`, `GradingPostgresTest`, `MyTasksPostgresTest`,
  `NotificationPostgresTest`, `DeadlineReminderPostgresTest`;
- `TaskStatusRulesTest`, `SubmissionServiceTest` (rules), `GradingServiceTest`, `MyTasksTest`,
  `NotificationServiceTest`.


## Sprint 3 — review fixes

Fixes from the review of Sprint 3, on top of the section below. Manual browser checks: the "Review fix" sections of
[docs/sprint3-manual-test-checklist.md](docs/sprint3-manual-test-checklist.md).

### Changed

- **Long documents are analyzed in full.** A text longer than `app.ai.max-input-chars` (now 15 000
  characters, about 5k tokens, so one call fits Groq's free per-minute token limit) used to be cut to one call that
  exceeded the limit; real syllabi over ~15 pages produced no drafts. Deadline extraction now splits the masked text
  on paragraph boundaries and sends the chunks one after another, with the usual retries. Their tasks are merged
  without duplicates (normalized title + deadline); summary and topics come from the first chunk that answered.
  Classification still reads only the beginning. If a chunk still fails, the drafts of the others are saved, existing
  assignments of the document are kept rather than removed, and `documents.ai_extraction_partial` is set: the drafts
  section shows "ИИ обработал документ не полностью — проверьте задания вручную". The next complete analysis clears it.
- **Inserts return only the new id (found by the PostgreSQL tests).** With JDBC generated keys the PostgreSQL driver
  appended `RETURNING *`, so every document insert also sent back the full `text_tsv` vector. Once
  `DocumentSearchSchema` added or rebuilt that column under a pooled connection, inserts failed with "cached plan must
  not change result type". `hibernate.jdbc.use_get_generated_keys=false`, set in code (`JpaSettings`, so it does
  not depend on the uncommitted `application.yml`), makes Hibernate use `INSERT … RETURNING id`.
- **CI** (`.github/workflows/ci.yml`): every push and pull request to `main` and `feature/**` runs all backend tests,
  including the PostgreSQL ones on a PostgreSQL 16 service container, and the frontend lint and build. It needs no
  secrets: AI, Google login, mail, OCR and S3 are off.
- **Google sign-in only when configured.** The Google client is registered only for a client ID that ends with
  `.apps.googleusercontent.com` and a non-empty secret, neither a placeholder (`GoogleLoginSettings`). Otherwise the
  app runs with password login, logs one warning naming the missing variable (never a value), and
  `GET /api/v1/auth/providers` answers `google: false`, so the login page hides the button instead of leading to
  Google's "invalid_client" error. A cancelled Google sign-in returns to the login page with "Вход через Google
  отменён"; other failures with "Не удалось войти через Google"; only the OAuth2 error code is logged.
- **Tests:** 444 backend tests. The 16 PostgreSQL tests (`*PostgresTest`) run in CI and on request (README
  "Тесты"); only the real-Tesseract smoke test stays opt-in. All 77 error codes have Russian texts and all 29 audit
  actions Russian labels.

### Security

- **Scans not checked for personal data.** A PDF or image with less than 100 characters of text and no
  successful OCR is treated as "personal data unknown": OCR queued, running or failed, or OCR switched off. Its
  `pii_count` of 0 counted no text, so it said nothing. For such a scan a student gets 403 `PII_ORIGINAL_RESTRICTED`
  ("Скан ещё не проверен на личные данные…") on view and download, unless the course teacher allowed the original.
  After a successful OCR the usual `pii_count` rule applies. `DocumentResponse.piiUnknown`; the teacher and ADMIN see
  the badge "Скан не проверен на личные данные".
- **Rule C covers failures.** Students never see a document whose AI classification failed
  (`ai_status FAILED`, which includes uploads while the AI is not configured) or whose OCR failed (`ocr_status FAILED`).
  An unrecognized scan typed `OTHER` from its file name is no longer visible to them. The rule
  (`AccessControlService.isHiddenFromStudents`, `DocumentRepository.VISIBLE_TO_STUDENTS[_SQL]`) applies to access by
  id, lists, search, course Q&A retrieval and the batch status poll. "Указать тип вручную" (`MANUAL`) makes the
  document visible according to its visibility. On the course page the teacher and ADMIN get "Требуют проверки: N",
  backed by `GET /api/v1/documents?courseId=…&needsReview=true`; for that course this needs `MANAGE_COURSE`. The upload
  result says why a file is hidden ("ИИ недоступен — укажите тип, чтобы студенты увидели файл").
- **Personal data.**
  - **Teacher phone.** Users have an optional phone, stored as digits only. Any user sets their name and phone in
    "Профиль" in the user menu (`PATCH /api/v1/users/me`; 400 `PHONE_INVALID`; audited as `PROFILE_UPDATED`,
    without values). The course teacher's phone stays readable in the course's documents, like their e-mail.
  - **Re-mask on change.** When a course gets another teacher, or its teacher changes their phone, the course's
    documents are marked outdated and re-masked on a background thread, with chunks rebuilt
    (`CoursePrivacyRemask`, `DocumentPrivacyBackfill.backfillCourses`).
  - **IINs in groups** (6+6 or 4+4+4 digits, one space or dash) follow the IIN rule. Dates, phones, card numbers and
    numbered orders are left alone. `PersonalDataMasker.VERSION` is 2, so stored texts are masked again at the next
    start.
  - **File names.** Students, and anyone who does not manage the course, see file names with personal data masked:
    in lists, search, the document page and text tab, Q&A sources, tasks and assignments, and the
    `Content-Disposition` of downloads. The course teacher and ADMIN see the real name.
  - **Q&A questions** are masked before they are sent to the AI.
  - **Fingerprint.** The public e-mail domains are sorted before hashing, so reordering them triggers no re-mask.
- **Encrypted document store (US-03).** The local storage encrypts every file, documents and task
  submissions alike, with AES-256-GCM when `STORAGE_ENCRYPTION_KEY` is set (`app.storage.encryption.enabled`, default:
  on exactly when a key is set). The format is a `UDENC` header with a version byte and a random 12-byte IV, then
  64 KiB segments, each a GCM message with its own tag. Both directions are streamed, so no file is ever held in memory
  whole, and downloads report the plaintext length. Changed, reordered or cut-off segments fail to decrypt.
  Files without the header are older plain files: they are still read as they are, and at startup
  `StorageEncryptionMigration` encrypts them through a temporary file and an atomic rename. It is idempotent and
  safe if interrupted. The application refuses to start with `enabled=true` and no key, or with a key that is not
  base64 of 32 bytes; the key is never logged. S3 puts carry server-side encryption (`app.storage.s3.sse`, default
  `AES256`; `none` for servers without SSE).

### Database (applied automatically at startup, safe with existing data)

| Change | How existing rows are handled |
|---|---|
| `documents.ai_extraction_partial` | NOT NULL with `DEFAULT false` |
| `users.phone` | nullable, digits only |

Checked by starting the committed Sprint 3 version on an empty database, adding users, a course, documents with
personal data, an active assignment with a student's task in progress and plain files, then starting this version
on it: the new columns were added with their defaults, the texts were masked again (`PersonalDataMasker` version 2)
and their chunks rebuilt, the files were encrypted, and the assignment and the task kept their status. A second start
had nothing to do; a start stopped during the startup jobs was finished by the next one.

### Configuration

New (all optional, defaults shown); see `application.example.yml` and `backend/.env.example`:

| Property | Env variable | Default |
|---|---|---|
| `app.storage.encryption.key` | `STORAGE_ENCRYPTION_KEY` | — (base64 of 32 bytes; encryption off without it) |
| `app.storage.encryption.enabled` | `STORAGE_ENCRYPTION_ENABLED` | empty = on when a key is set |
| `app.storage.s3.sse` | `S3_SSE` | `AES256` |

Changed default: `app.ai.max-input-chars` (`AI_MAX_INPUT_CHARS`) is now `15000` instead of `60000`; it is the size of one
deadline-extraction call, no longer a cut-off. Update your local `application.yml` if it still says `60000`.

### Known limitations

- Masking finds the formats it knows: IINs (also in 6+6 and 4+4+4 groups), KZ phones, e-mails and ID / passport
  numbers. Names and addresses stay, in texts and in file names: "Иванов_А.pdf" stays readable for students. An IIN
  in groups right after "№", "No" or "номер" is taken for an order number unless the "ИИН" label is there too.
- Only the course teacher's own e-mail and phone stay readable; a co-teacher's or a secretary's contacts are masked.
  E-mails cannot be changed in the profile. The background re-mask after a teacher or phone change takes a moment.
  Until it is done, students still see the old masking, and the next start finishes it if the server stopped.
- The AI's answer is shown as it comes. It is built only from masked text and a masked question, so it can only
  repeat a placeholder, but it is not masked again.
- OCR reads only the first 20 pages of a PDF; handwriting and low-quality scans may give poor text, shown as
  "Распознано со скана".
- Storage encryption: without `STORAGE_ENCRYPTION_KEY` the local files stay plain (the startup log warns). The key
  cannot be changed yet: files written with it are unreadable without it, so keep a backup. Tesseract's temporary
  page images during OCR are plain files in the system temp folder, deleted after recognition.
- Deadline changes are not yet sent to students (the `AssignmentEvents` hook is empty until Sprint 4).
- The PostgreSQL tests (`*PostgresTest`, see README "Тесты") run in CI and locally only on request against
  `doc_assistant_test`, not in the normal local build. The upgrade check above used a small hand-made data set, not
  a copy of a real database.
- Q&A (course and document chat) calls the AI in the request thread, outside any transaction, with at most
  `app.ai.max-concurrent-questions` at once; the rest get 503 `AI_BUSY` after 2 seconds.
- Nothing in the app releases an `EXAM_ANSWERS` document to students: it stays `TEACHER_ONLY`, also after its type is
  changed by hand.
- A published assignment keeps its source document's id and (masked) name for students even after that document is
  hidden from them, e.g. typed `EXAM_ANSWERS` later; the document itself stays closed to them (403).

## Sprint 3 — Review of AI results, personal data protection, OCR

US-08 (the teacher reviews what the AI found before students see it), US-09 (personal data is masked for students,
search, Q&A and the AI), US-07 (scanned PDFs and images get their text by OCR), plus the UI audit fixes. Manual
browser checks: [docs/sprint3-manual-test-checklist.md](docs/sprint3-manual-test-checklist.md).

### Added

- **AI drafts (US-08).** Assignments and deadlines the AI finds in a syllabus or assignment are created as `DRAFT`:
  students do not see them and get no tasks until the course teacher publishes them. New status `DRAFT` next to
  `ACTIVE` and `CLOSED`. `app.ai.auto-publish-assignments=true` restores the Sprint 2 behavior (published at once).
- **Review endpoints** (course teacher or ADMIN, `MANAGE_ASSIGNMENTS`):
  - `GET /api/v1/courses/{id}/assignments/drafts`;
  - `POST /api/v1/assignments/{id}/publish` and `POST /api/v1/courses/{id}/assignments/publish` (several drafts, all
    or nothing; tasks are handed out once);
  - `PATCH /api/v1/assignments/{id}` (title, requirements, deadline; for `DRAFT`, `ACTIVE` and `CLOSED`);
  - `POST /api/v1/assignments/{id}/close` and `POST …/reopen`;
  - `POST /api/v1/assignments/{id}/not-duplicate`.
- **Edits reach the students' tasks without touching their progress:** title, description and deadline are copied
  into the tasks; statuses and submissions stay. A deadline change of a published assignment is audited with the old
  and new value (`ASSIGNMENT_DEADLINE_CHANGED`) and reported to `AssignmentEvents` (notifications follow in Sprint 4).
  An edited, closed or reopened assignment is "taken over" by the teacher: a later re-analysis of its document never
  changes, duplicates or removes it.
- **Duplicate drafts are marked, never merged:** a new draft that looks like an assignment already in the course
  (the same normalized title, or "Лаб. 1" vs "Лабораторная работа №1" with deadlines at most a day apart; different
  numbers never match) is flagged
  "Похоже на …". The teacher deletes it or clears the mark ("Это другое задание").
- **Masked copy of every text (US-09):** IIN, Kazakh phone numbers, e-mails and ID card / passport numbers are
  replaced with `[ИИН скрыт]`, `[телефон скрыт]`, `[e-mail скрыт]`, `[номер документа скрыт]` in
  `documents.masked_text`. The course teacher's e-mail and e-mails on `app.privacy.public-email-domains` stay readable.
  The AI (classification, deadline extraction, Q&A), snippets, Q&A chunks and full-text search use only the masked
  copy; file names are masked in everything sent to the AI. The original stays in `extracted_text`.
- **Text tab:** `GET /api/v1/documents/{id}/text` returns the original text to the course teacher and ADMIN and the
  masked text to everyone else. "Личные данные: N" badge on documents with personal data.
- **Original files with personal data:** a student gets 403 `PII_ORIGINAL_RESTRICTED` on view and download unless the
  course teacher allowed it (`POST /api/v1/documents/{id}/allow-original`, `…/deny-original`). Every opening of such an
  original, by the teacher, ADMIN or an allowed student, is audited (`PII_ORIGINAL_VIEWED`).
- **OCR for scans (US-07):** a PDF or image with less than 100 characters of text is queued for Tesseract (`rus+kaz+eng`,
  first 20 pages, 120 s timeout). OCR runs only in the background job: OCR → masking and Q&A chunks → classification
  → deadline drafts. The upload never runs it. A failure, a timeout or an empty result keeps the document and marks it
  `ocr_status = FAILED`. Tesseract and its languages are checked once at startup; documents waiting for OCR are queued
  again after a restart.
- **"Нужна проверка" for failed scans** (course teacher or ADMIN, `MANAGE_COURSE`):
  - `POST /api/v1/documents/{id}/category` ("Указать тип вручную"): same rules as an AI result;
  - `POST /api/v1/documents/{id}/ocr/retry` ("Повторить распознавание"): a type the AI chose is chosen again from the
    new text; a type chosen by hand stays.
  - Errors: 409 `DOCUMENT_BEING_PROCESSED`, 409 `OCR_NOT_FAILED`, 503 `OCR_UNAVAILABLE`.
- **Frontend:**
  - "Черновики от ИИ" on the course page (edit, delete, publish, publish all, duplicate mark);
  - "Изменить", "Закрыть задание" and "Открыть снова" on assignments;
  - the "Текст" tab, `PiiBadge`, the restricted-original notice and the "Разрешить студентам оригинал" switch;
  - "Распознаём скан…" while OCR runs, "Распознано со скана", the "Нужна проверка" panel and `SetCategoryDialog`;
  - the upload notice counts drafts for the teacher.
- **UI audit:**
  - responsive navbar with a burger menu and a user menu;
  - confirm dialogs for destructive actions;
  - one date format everywhere (`utils/dates.js`);
  - dialog focus handling (`useDialogFocus`), accessible labels, contrast fixes.
- **README:** section "OCR (распознавание сканов)" with the Windows setup of Tesseract (UB Mannheim, Russian and
  Kazakh language data).
- **Tests:** 364 backend tests (15 opt-in ones skipped by default). `Sprint3QaTest` mirrors QA-AI-008, QA-SEC-009 and
  QA-OCR-007 end to end, with the AI and Tesseract mocked and every request to the AI checked for personal data.

### Changed

- **Student work is never destroyed by teacher or admin actions.** An assignment whose students already worked on it
  (status changed or file handed in) is closed instead of deleted. This covers re-analysis, deleting its document and
  deleting it by hand (409 `ASSIGNMENT_HAS_ACTIVITY`, close it instead). A student who leaves a course keeps their tasks.
- **Upload parsing no longer runs Tika's built-in OCR;** scans go through the background OCR job instead.
- **Full-text search** runs over `masked_text`, so searching for an IIN or a phone number finds nothing.
- **Stable error codes:** every code in `ErrorCodes` has a Russian text in `CODE_MESSAGES`. New audit actions have
  Russian labels in the admin log.

### Security

- Rules A–C from Sprint 2 also cover the new paths: a type set by hand to `EXAM_ANSWERS` becomes `TEACHER_ONLY` and
  is never analyzed; a document being re-recognized is hidden from students again until it is classified.
- Every new endpoint is authorized in its service through `AccessControlService` and `Permission`; there are no role
  checks in the new controller methods.
- No AI or OCR call runs in a request thread or inside a database transaction. The only exception is Q&A (course
  and document chat), which calls the AI in the request thread as before. Jobs are submitted after commit.

### Database (applied automatically at startup, safe with existing data)

| Change | How existing rows are handled |
|---|---|
| `assignments.status` check constraint | rebuilt from the enum to accept `DRAFT` (`AssignmentStatusMigration`); rows keep their status |
| `assignments.ai_match_key`, `assignments.duplicate_of_id` | nullable; `duplicate_of_id` is `ON DELETE SET NULL` |
| `documents.masked_text`, `pii_types`, `pii_scan` | nullable; `DocumentPrivacyBackfill` masks every document with text at startup, 50 per batch, and again when the privacy settings change |
| `documents.pii_count`, `original_allowed_for_students`, `text_from_ocr` | NOT NULL with `DEFAULT 0` / `false` |
| `documents.ocr_status` | nullable (`null` = no OCR needed) |
| `documents.text_tsv` | dropped and recreated once over `masked_text` if it was generated from `extracted_text`; GIN index recreated |
| `document_chunks` | rebuilt from the masked text by the backfill |

### Configuration

New (all optional, defaults shown); see `application.example.yml`, `backend/.env.example` and the README:

| Property | Env variable | Default |
|---|---|---|
| `app.ai.auto-publish-assignments` | `AI_AUTO_PUBLISH_ASSIGNMENTS` | `false` |
| `app.privacy.enabled` | `PRIVACY_ENABLED` | `true` |
| `app.privacy.public-email-domains` | `PRIVACY_PUBLIC_EMAIL_DOMAINS` | — |
| `app.ocr.enabled` | `OCR_ENABLED` | `false` |
| `app.ocr.tesseract-path` | `OCR_TESSERACT_PATH` | — (PATH) |
| `app.ocr.languages` | `OCR_LANGUAGES` | `rus+kaz+eng` |
| `app.ocr.timeout` | `OCR_TIMEOUT` | `120s` |
| `app.ocr.max-pages` | `OCR_MAX_PAGES` | `20` |

### Known limitations

See "Sprint 3 — review fixes" below for the current list.

## Sprint 2 — gap closure

Closes the gaps between the Sprint 2 spec (US-04 course-bound upload, US-05 AI classification, US-06 ZIP import)
and the code. Manual browser checks: [docs/sprint2-manual-test-checklist.md](docs/sprint2-manual-test-checklist.md).

### Added

- **Background AI classification (US-05).** Uploads without a category are stored immediately as `OTHER` with
  `aiStatus = PENDING`; `DocumentProcessingService` classifies them on the AI executor (Groq,
  `openai/gpt-oss-120b`, JSON mode) and stores category, type and the model's confidence. An upload never waits for
  the AI. New `documents.ai_status`: `PENDING`, `PROCESSING`, `DONE`, `FAILED`, `MANUAL`.
- **Classification history with confidence:** `document_classifications` (one row per document).
- **Deadline extraction right after classification:** SYLLABUS and ASSIGNMENT documents are analyzed in the same
  background job; assignments are created for the course and handed out to enrolled students as tasks.
- **Startup recovery:** documents left `PROCESSING` by a restart go back to `PENDING` and are queued again
  (`AiProcessingRecovery`).
- **ZIP import history (US-06):** `batch_imports` records every archive (total vs. imported files, uploader);
  documents link to their import. `GET /api/v1/courses/{id}/imports` (course teacher, ADMIN).
- **Endpoints:** `GET /api/v1/documents/{id}/status` (aiStatus, category, confidence, assignments count),
  `POST /api/v1/documents/{id}/reclassify` (course teacher, ADMIN).
- **Content-based upload validation:** `FileTypeValidator` detects the real type with Apache Tika; a file whose bytes
  do not match its extension is refused ("Содержимое файла не соответствует расширению"); the detected type is stored.
- **Storage abstraction:** `StorageService` with `LocalStorageService` (default) and `S3StorageService`
  (`app.storage.driver=s3`, AWS S3 or MinIO, AWS SDK v2 2.54.20 with the JDK HTTP client).
- **Frontend:** per-file status in the upload panel ("Загружено" → "ИИ определяет тип…" → category · confidence),
  deadline notice with a link to the course's assignments, "Импортировано X из Y файлов" for ZIPs, `CategoryBadge`
  in all document lists, "Переклассифицировать" for teachers.
- **Maven Wrapper 3.3.4** (`backend/mvnw`, `mvnw.cmd`; Maven 3.9.9), works with paths containing spaces.
- **Tests:** 129 backend unit tests, including `Sprint2QaTest` mirroring QA-UPL-04, QA-ML-05, QA-UPL-06.

### Changed

- **Categories:** `SYLLABUS, ASSIGNMENT, LECTURE, EXAM_ANSWERS, REGULATION, OTHER`; the classifier picks only these.
  `TRANSCRIPT` and `THESIS` remain for old rows. `REGULATIONS` rows are renamed to `REGULATION` at startup.
- **AI model:** `openai/gpt-oss-120b`; the spec's `llama-3.3-70b-versatile` is not available to the project's Groq
  key. At startup the backend checks the configured model against `GET /models` and warns if it is missing.
- **AI client:** retries 429 and 5xx (Retry-After, else 2 s / 4 s, `app.ai.max-attempts`, default 3); new `chatJson`.
- **Stable error codes:** every error response carries `code` (66 codes in `ErrorCodes`; `ApiException` cannot be
  created without one). The frontend translates by code first (`CODE_MESSAGES` in `api/axios.js`), then by the old
  message translations, then a generic text. Removed the test endpoints `/api/v1/test/*` (`TestRoleController`).
- **Single upload** (`POST /documents/upload`) now accepts only the supported formats, like the multi-file upload.
- `UploadedDocument.aiClassified` is always `false` (classification happens after the response); kept for clients.

### Security

- **Login brute-force protection:** 5 failed logins per (email, client IP) within 15 min block that pair for 15 min;
  30 failures per IP (any emails) block the IP. An email is never locked globally. The block is checked before the
  password, a success resets the pair, lockouts are audited (`LOGIN_LOCKED`), the answer is 429 with minutes left.
  Client IP = TCP peer unless `app.security.trust-forwarded-for=true` (env `TRUST_FORWARDED_FOR`).
- **Course enrollment only by invitation link:** students can no longer enroll by the (sequential) course id.
  Each course has a secret `invite_code` (128-bit, URL-safe); students join via `/join/{code}`
  (`POST /api/v1/courses/join`), at most 10 wrong codes per user per 10 minutes (then 429), failures audited.
  Teachers/ADMIN read and regenerate the code (`GET /courses/{id}/invite`, `POST /courses/{id}/invite/regenerate`).
- **No teacher self-registration:** registration and Google sign-up always create `ROLE_STUDENT`. "Я преподаватель"
  only records a request (`users.teacher_requested`) that ADMIN approves or rejects in "Заявки преподавателей"
  (`GET /api/v1/admin/teacher-requests`, `POST …/{id}/approve`, `POST …/{id}/reject`, audited).
- **Rule A:** an `EXAM_ANSWERS` document is always `TEACHER_ONLY` — chosen by hand, found by the AI, or already in the
  database (forced at startup).
- **Rule B:** exam answers are never analyzed into assignments (`POST /analyze` → 400, background analysis skips them).
- **Rule C:** students never see a document while the AI is classifying it (`PENDING`/`PROCESSING`) — in single access
  checks, document lists, full-text search and course-wide Q&A.

### Database (applied automatically at startup, safe with existing data)

| Change | How existing rows are handled |
|---|---|
| `documents.ai_status` NOT NULL | Hibernate adds it with `DEFAULT 'DONE'` |
| `documents.batch_import_id` | nullable, `ON DELETE SET NULL` |
| `documents.category` check constraint | rebuilt from the enum; retired values → `OTHER`, `REGULATIONS` → `REGULATION` |
| `documents.type` backfill | set from category where a same-named type exists |
| `document_classifications`, `batch_imports` | new tables |

### Configuration

New (all optional, defaults shown) — see `application.example.yml` and `backend/.env.example`:

| Property | Env variable | Default |
|---|---|---|
| `app.ai.max-attempts` | `AI_MAX_ATTEMPTS` | `3` |
| `app.ai.max-concurrency` | `AI_MAX_CONCURRENCY` | `2` in the example (Groq free tier) |
| `app.storage.driver` | `STORAGE_DRIVER` | `local` |
| `app.storage.s3.bucket` / `region` / `endpoint` / `path-style-access` | `S3_BUCKET` / `S3_REGION` / `S3_ENDPOINT` / `S3_PATH_STYLE_ACCESS` | — / `eu-central-1` / — / `false` |

S3 credentials come from the AWS default chain (real environment variables or `~/.aws/credentials`), not from
`backend/.env`.

### Known limitations

- A document whose classification **FAILED** is shown to students with the uploader's visibility; if it really was
  exam answers, it is not hidden.
- **Reclassifying** a document into `EXAM_ANSWERS` keeps the assignments that were found in it earlier, so their
  titles and deadlines stay visible to students as tasks.
- Course Q&A calls the AI in the request thread (by design); with 429 retries a question can now wait up to ~40 s.
- The upload panel polls one status request per file every 2 s (a 15-file ZIP = 15 requests per tick).
- Not yet verified against a running PostgreSQL in CI: the JPQL queries and startup migrations are covered only by
  starting the app.
