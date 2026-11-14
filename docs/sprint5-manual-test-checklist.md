# Sprint 5 — manual browser test checklist

Accounts and two courses:

| Account | Role | Relation |
|---|---|---|
| **T** | TEACHER | teacher of INF210 «Базы данных» |
| **T2** | TEACHER | teaches another course, not INF210 |
| **S** | STUDENT | enrolled in INF210 and in one more course |
| **D** | ADMIN (dean's office) | — |

Use separate browser profiles (or a normal and a private window) for two accounts at once. Automated coverage of the
same cases: `Sprint5QaPostgresTest` (QA-ASK-014, QA-SRC-015, QA-WEEK-016) with the AI mocked. This list checks what
those tests cannot: the browser, the real AI (Groq), a phone width of 375 px, the dark theme, keyboard use, scrolling
to a fragment and an upgrade of an existing database.

## Prerequisites

- [ ] Back up the working database, then start the backend once against it (it **already has Sprint 4 data**). It
      starts without manual steps; the log has a Hibernate line about `uk_course_weeks_course_week` once (the new
      unique key). A second start shows nothing new.
- [ ] `AI_API_KEY` in `backend/.env` is a working Groq key (`GET /api/v1/ai/status` says `available: true`).
- [ ] Files ready: a syllabus with a week plan («Неделя 1 — …», «Неделя 2 — …»), `Week 3 slides.pdf`, a lecture
      without a week in its name, a file with an IIN in its text.

## AI assistant (US-14) — S

- [ ] The navbar has «ИИ-ассистент»; the dashboard has the card «ИИ-ассистент». Both open `/assistant`.
- [ ] «Где искать ответ» is «Все мои курсы»; typing two letters of a course code lists that course; picking it changes
      the chat title to «Вопрос по курсу «…»».
- [ ] «Когда сдавать лабораторную 2?» (real AI): the answer names lab 2's date and cites a fragment. Each source card
      shows the file and the course, no «ID документа».
- [ ] T moves lab 2's deadline in «Задания курса»; asked again, the AI gives the new date and says it was moved. Under
      the sources, «Задания курса» shows lab 2 with the new deadline and «К заданиям курса»; «З1» in the text points
      at that card.
- [ ] «Какие у меня пропущенные задания или дедлайны?» with one overdue task: the AI names it as overdue; «Мои задачи»
      under the answer shows it (red «Просрочено»), «К моим задачам» opens the dashboard scrolled to «Мои задачи».
      With nothing overdue the AI says so instead of «нет информации».
- [ ] The same question as T: the AI says personal tasks are for students and lists upcoming course deadlines.
- [ ] An answer without citations shows «ИИ не указал конкретные фрагменты. Показаны все, по которым он отвечал».
- [ ] A new student with no course: `/assistant` shows «Вы пока не записаны ни на один курс» with «Курсы».

## Answer sources (US-15)

- [ ] S: in a source card, «Показать в документе» opens «Текст» of the document with the fragment marked and scrolled
      into view, and the bar «Показан фрагмент, на который сослался ИИ». «Снять выделение» removes it; placeholders
      like «[ИИН скрыт]» stay highlighted inside and outside the mark.
- [ ] T on the same link: the bar explains the copy with hidden personal data; «Показать оригинал» shows the original
      with the IIN and no mark.
- [ ] Reprocess the document (T: «Переклассифицировать», or replace the text) and open an old link: the fragment is
      found by its text, or «Документ изменился, фрагмент не найден».
- [ ] Change `from`/`to` in the address to `abc`, `-1` or `to` < `from`: the text opens without a mark.
- [ ] On a document page ask in «Чат с ИИ», click «Показать в тексте» in a source, then go back to «Чат с ИИ» by hand:
      the conversation is still there and the address has no `from`/`to`.

## Course weeks (US-16) — T

- [ ] A course without a plan: «Документы курса» opens «Списком» (as before); «По неделям» shows «Загрузите силлабус —
      ИИ составит план недель…».
- [ ] Upload the syllabus: after the AI is done, «По неделям» lists «Неделя 1 · …», «Неделя 2 · …»; empty weeks are
      dimmed; «Без недели» comes last.
- [ ] Upload `Week 3 slides.pdf`: it shows «ИИ предлагает: неделя 3»; «Принять» moves it to week 3, the counters
      update. «Другая» focuses the week select.
- [ ] Upload a lecture without a week: after a few seconds it gets a hint from the plan (real AI), or none.
- [ ] «Предложить недели»: «ИИ подбирает недели для N документов», the counters refresh every few seconds and stop
      after two minutes at the latest. «Принять все подсказки (N)» asks first and then moves them all.
- [ ] The pencil on a week: type a topic, Enter saves, Escape cancels; a later syllabus keeps that topic.
- [ ] «Настроить недели»: 10 saves; a number below a week with documents shows «В неделях после нового числа есть
      документы…» under the field.
- [ ] S sees only weeks with documents visible to them, no selects, no hints, no buttons; a TEACHER_ONLY file in a
      week is not counted for S.
- [ ] T2 opens the course URL: no access, no week data.
- [ ] D: the log shows «Неделя документа» (with «AI suggestion accepted» or «by hand») and «Изменение недель курса».

## Phone width, dark theme, keyboard

- [ ] 375 px: `/assistant`, source and task cards, the text bar of a fragment, the weeks sections with their selects
      and buttons fit without horizontal scrolling.
- [ ] Dark theme: the marked fragment, the hint chip, dimmed weeks and the task cards stay readable.
- [ ] Keyboard only: open a week section (Enter/Space), change a document's week, accept a hint, edit a topic, open
      and save «Настроить недели», follow «Показать в документе».
