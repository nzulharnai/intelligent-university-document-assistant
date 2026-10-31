# Sprint 4 — manual browser test checklist

Accounts and one course:

| Account | Role | Relation to INF345 |
|---|---|---|
| **T** | TEACHER | teacher of INF345 |
| **T2** | TEACHER | teaches another course, not INF345 |
| **S** | STUDENT | enrolled in INF345, e-mail notifications on |
| **S2** | STUDENT | enrolled in INF345, e-mail notifications off (step 1 of "Notifications") |
| **D** | ADMIN (dean's office) | — |

Use separate browser profiles (or a normal and a private window) for two accounts at once. Automated coverage of the
same cases: `Sprint4QaPostgresTest` (QA-SUB-010, QA-GRD-011, QA-UI-012, QA-NOT-013). This list checks what those
tests cannot: the browser, real e-mail, the scheduler on its own clock, and an upgrade of an existing database.

## Prerequisites

- [ ] Back up the working database, then start the backend once against it (it **already has Sprint 3 data**). The
      log at startup shows `App time zone: Asia/Almaty` and, if there were submissions or tasks marked done,
      `Task statuses migrated: N task(s) with submissions -> SUBMITTED, M completed task(s) without submissions ->
      IN_PROGRESS`. A second start shows no such line.
- [ ] SMTP in `backend/.env` works (the OTP mail of a new registration arrives), so notification e-mails can be checked.
- [ ] For the reminder checks, either set `REMINDER_CRON=0 * * * * *` (every minute) in `backend/.env` and restart, or
      use the manual run of the dean's office (see "Deadline reminders").
- [ ] Files ready: `solution.pdf`, `solution-v2.docx`, and something unsupported (`run.exe`).

## Teacher of INF345 (T)

**Assignment settings (US-10)**
- [ ] "Задания курса" → "Изменить" on an assignment: "Разрешить сдачу после срока" (on by default) and "Максимальный
      балл" (empty = no points). Set 10, save, open again: the values are kept.
- [ ] A max score of 0 or 1001 is not accepted by the field.
- [ ] "Добавить задание" with a deadline tomorrow: S and S2 get the task; S sees "Новое задание: …" in the bell.

**Grading page (US-11)**
- [ ] "Оценки" on the assignment opens `/courses/<id>/assignments/<id>`: deadline, "Макс. балл: 10", "После срока
      принимается" / "не принимается", and "Сдали X из Y · с опозданием Z · оценено W" with a progress bar.
- [ ] Filters "Не сдали", "Ждут оценки", "С опозданием", "Оценено": the list follows; an empty filter shows "Нет
      студентов с таким состоянием."
- [ ] "Оценить" on a student who handed in: the current version's files download; 12 shows "Оценка должна быть от 0
      до 10" under the field; 8,5 with feedback → "Оценка сохранена", the row shows "Оценено · 8.5 / 10".
- [ ] "Следующий студент" after changing a grade saves it and opens the next student; without a change it only moves on.
- [ ] Grade a student who handed in nothing with 0 ("Не сдано").
- [ ] "Изменить" → "Снять оценку" → confirm: back to "Сдано" (or "Не начато" without a hand-in); files stay.
- [ ] Phone width (375 px): header, filters and rows fit without horizontal scrolling.

## Teacher of another course (T2)

- [ ] Open the grading page URL of the INF345 assignment: "Нет доступа к оценкам", no student data.

## Student of INF345 (S)

**Submitting (US-10)**
- [ ] "Мои задачи": an assignment task offers only "Не начато" / "В работе".
- [ ] "Сдать" with `solution.pdf`: "Работа сдана"; the chip is "Сдано", the select is gone, the button says
      "Сдать заново", "Моё решение" shows "Версия 1 · <time>".
- [ ] "Сдать заново" with `solution-v2.docx`: "Версия 2 · … · текущая" on top, "Прежняя версия 1" collapsed and
      expandable.
- [ ] `run.exe` is refused with "неподдерживаемый формат" before anything is sent.
- [ ] T moves the deadline into the past (late work allowed): "Сдать заново" first warns "Срок прошёл — работа будет
      отмечена как сданная с опозданием"; after it the chip is "Сдано с опозданием" with a clock.
- [ ] T turns off "Разрешить сдачу после срока": the button is disabled, next to it "Срок сдачи истёк … — работы после
      срока не принимаются".
- [ ] T closes the assignment: the button is disabled with "Задание закрыто — новые решения не принимаются".
- [ ] After T grades it: "Работа оценена — сдать заново нельзя".

**"Мои задачи" (US-12)**
- [ ] The summary "К сдаче: N · Просрочено: M · Оценено: K"; clicking a number applies that filter.
- [ ] Deadline hints: "Сегодня до 23:59", "Завтра до …", "Через 2 дня · …", "Просрочено на 1 день · …".
- [ ] An overdue, not handed-in task has a red left edge and the "Просрочено на …" tag with a warning icon.
- [ ] Graded work shows "8.5 / 10"; "Отзыв преподавателя" expands the teacher's text.
- [ ] The course filter (with two or more courses) combines with the state chips.
- [ ] A filter with no result: "По фильтру ничего не найдено" → "Сбросить фильтры" brings everything back.
- [ ] A new student with no tasks: "Задач нет".
- [ ] Hand in from the "К сдаче" filter: the task leaves the list, the number drops, "Ближайшие дедлайны" on the
      dashboard drops it at once.
- [ ] Stop the backend and reload: "Не удалось загрузить задачи" with "Повторить".

## Notifications (US-13)

- [ ] S2 opens "Профиль" and clears "Присылать уведомления на почту" ("Письма с уведомлениями отключены").
- [ ] T publishes two drafts at once. S and S2: the bell shows a count; the panel says "Новые задания: 2" with both
      titles. S gets one e-mail with "Открыть в UniDocs" and "Отключить письма можно в профиле"; S2 gets none.
- [ ] Clicking a notification opens "Панель" and lowers the count; "Прочитать все" clears it.
- [ ] `/notifications` ("Все уведомления"): newest first, "Показать ещё" with more than 20; "Прочитать все" there also
      clears the bell at once.
- [ ] T moves an active deadline: "Срок изменён: …" with the old and new date ("без срока" when cleared).
- [ ] T grades S: "Работа оценена: …", "Оценка: 8.5 из 10".
- [ ] Switch to another tab for 2+ minutes (DevTools → Network): no `unread-count` requests; coming back fetches it
      at once.
- [ ] Break SMTP (wrong password in `.env`, restart): publishing and grading still work; the log shows `Notification
      e-mail … failed`, never an address.

**Deadline reminders**
- [ ] S has a task due in the next 24 hours, nothing handed in. Wait for the cron or run it as D (below): S gets
      "Срок сдачи «…» — завтра в …" (or "сегодня в …"), once. The log shows `Deadline reminders: N sent`.
- [ ] A second run sends nothing for the same task.
- [ ] No reminder for a handed-in task, a closed assignment, a draft, or a student who left the course.
- [ ] T moves that deadline later (still within 24 h): the next run reminds again. Moving it earlier does not.

## Dean's office (D, ADMIN)

- [ ] Manual reminder run (demo): with D's token from the browser (DevTools → Application → localStorage):
      ```powershell
      Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/admin/notifications/run-reminders `
        -Headers @{ Authorization = "Bearer <token>" }
      ```
      Answer `sent = N`; the log of "Панель деканата" shows "Запуск напоминаний о сроках". As T or S the same call
      is 403.
- [ ] Approve or reject a teacher-role request: the applicant, and only they, gets "Заявка на роль преподавателя
      одобрена / отклонена".
- [ ] The log shows "Сдача решения" (with "late" and the version), "Оценка работы" ("— -> 8.5", "8.5 -> 9"),
      "Снятие оценки".
- [ ] D can open any course's grading page.

## Upgrade of an existing database

- [ ] After the first start: tasks that had files are "Сдано" (late where the last file came after the deadline);
      tasks of assignments that were marked "Выполнено" without files are "В работе"; tasks without an assignment
      keep their status.
- [ ] Existing submissions show as "Версия 1" with all their files.
- [ ] Existing assignments accept late work and have no max score until the teacher sets one.
- [ ] Every existing user has e-mail notifications on.
