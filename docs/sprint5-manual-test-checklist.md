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

## Acceptance review

### Saved conversations and limits (US-14 AC-4, AC-5) — S

- [ ] `/assistant`: «История» is empty («Здесь появятся ваши разговоры с ИИ…»). Ask «Когда сдавать лабораторную
      работу 2?» in INF345: the conversation appears in «История» with «INF345 · …» and «1 вопрос».
- [ ] Ask «а третью?»: the AI understands it is about lab 3; «История» shows «2 вопроса».
- [ ] Reload the page (QA-AI-014 step 4) and click the conversation: both questions and answers are back, with their
      source cards; «Показать в документе» still works. A new question continues it.
- [ ] Choose another course in «Где искать ответ»: the chat starts empty (a new conversation).
- [ ] Ask on the course page in «Задать вопрос по курсу»: the question appears in «История» of `/assistant`.
- [ ] The bin on a conversation asks first, then removes it; the open chat starts over if it was that one.
- [ ] S2 (another student) opens `/api/v1/ai/conversations/{id}` of S's conversation: 404.
- [ ] With `AI_QUESTIONS_PER_HOUR=3`: the 4th question within the hour answers «Лимит вопросов к ИИ: 3 в час.
      Следующий вопрос можно задать через N мин.»; the note next to the course select says the limit is reached.
- [ ] A question the documents do not answer (QA-UI-015 step 4) has the tag «Не найдено в документах».

### Hidden weeks, assignments in weeks, filters (US-16 AC-1, AC-4, AC-5) — T, then S

- [ ] «Настроить недели»: 14 (QA-CRS-016 step 1); weeks 1..14 are listed.
- [ ] Open a week: «Задания (N)» lists its assignments; the select on an assignment moves it to week 5; week 5's
      header shows the assignment count, the log has «Неделя задания».
- [ ] «Настроить недели» below a week with only an assignment: «В неделях после нового числа есть документы или
      задания…».
- [ ] The eye on week 6 (QA-CRS-016 step 3): «Скрыта от студентов», the week is dimmed and its panel explains it.
- [ ] S: the course page lists the weeks in order without week 6; its documents are not in «Списком», not found by
      search, not used by the AI, and their direct links answer «Нет доступа».
- [ ] T shows week 6 again: S sees it after a reload.
- [ ] «Категория: Лекция» (QA-CRS-016 step 4): only lectures, as a list; «Загружены с … по …» narrows by date (both
      days included); «с» after «по» shows «Дата «с» позже даты «по»» and loads nothing; «Сбросить» brings the weeks
      view back.

