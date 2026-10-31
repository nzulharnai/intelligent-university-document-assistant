import axios from 'axios'

// Backend origin; override with VITE_BACKEND_URL (e.g. in .env.local or for a deployment).
const BACKEND_URL = import.meta.env.VITE_BACKEND_URL ?? 'http://localhost:8080'
export const API_BASE_URL = `${BACKEND_URL}/api/v1`
export const GOOGLE_AUTH_URL = `${BACKEND_URL}/oauth2/authorization/google`
export const TOKEN_KEY = 'token'

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// A 401 on a protected endpoint means the token is expired or revoked: tell AuthContext to drop the session.
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const isAuthEndpoint = error.config?.url?.startsWith('/auth/')
    if (error.response?.status === 401 && !isAuthEndpoint) {
      localStorage.removeItem(TOKEN_KEY)
      window.dispatchEvent(new Event('auth:logout'))
    }
    return Promise.reject(error)
  },
)

// Fallback for responses without a known code (older backends): English backend messages mapped to Russian.
// Kept for backward compatibility; CODE_MESSAGES below is consulted first.
const MESSAGE_TRANSLATIONS = {
  'Invalid email or password': 'Неверный email или пароль',
  'An account with this email already exists': 'Аккаунт с таким email уже существует',
  'ADMIN role cannot be self-assigned':
    'Роль «Деканат» нельзя выбрать при самостоятельной регистрации. Обратитесь в деканат.',
  'This account uses Google sign-in. Please log in with Google.':
    'Этот аккаунт зарегистрирован через Google. Войдите через Google.',
  'Invalid or expired code': 'Неверный или просроченный код',
  'Code has expired. Please request a new one.': 'Срок действия кода истёк. Запросите новый.',
  'Too many failed attempts. Please request a new code.':
    'Слишком много неудачных попыток. Запросите новый код.',
  'New password must differ from the current one': 'Новый пароль должен отличаться от текущего',
  'Could not send email. Please try again later.': 'Не удалось отправить письмо. Попробуйте позже.',
  'Malformed request body or invalid field value': 'Некорректные данные запроса',
  'Request conflicts with existing data': 'Такие данные уже существуют',
  'Access denied': 'Доступ запрещён',
  'Document not found': 'Документ не найден',
  'File is empty': 'Файл пустой',
  'File is too large': 'Файл слишком большой (максимум 20 МБ)',
  'Preview is not available for this file type': 'Предпросмотр недоступен для этого формата',
  'User not found': 'Пользователь не найден',
  'You cannot change your own role': 'Нельзя изменить собственную роль',
  'You cannot delete your own account': 'Нельзя удалить собственный аккаунт',
  'Course not found': 'Курс не найден',
  'Task not found': 'Задача не найдена',
  'Teacher request not found': 'Заявка уже обработана или не найдена',
  'Only TEACHER or ADMIN can upload TEACHER_ONLY documents':
    'Видимость «Только преподаватели» доступна только преподавателям и деканату',
  'courseId is required for COURSE_STUDENTS visibility': 'Для видимости «Студенты курса» выберите курс',
  'You are not a member of this course': 'Вы не состоите в этом курсе',
  'Only students can enroll in a course': 'Записаться на курс может только студент',
  'Only the course teacher or ADMIN can enroll other users': 'Записывать других может только преподаватель курса',
  'Only the owner, the course teacher or ADMIN can analyze this document':
    'Запустить анализ может владелец документа, преподаватель курса или деканат',
  'The document has no extracted text to analyze': 'В документе нет распознанного текста для анализа',
  'You can change only your own tasks': 'Можно менять только свои задачи',
  'AI assistant is not configured': 'ИИ-ассистент не подключён на сервере. Обратитесь в деканат.',
  'AI analysis is not configured': 'ИИ-ассистент не подключён на сервере. Обратитесь в деканат.',
  'AI assistant is busy, try again later': 'ИИ-ассистент сейчас перегружен. Попробуйте через минуту.',
  'AI analysis queue is full, try again later': 'Очередь ИИ-анализа заполнена. Попробуйте через минуту.',
}

export const AI_UNAVAILABLE_MESSAGE = 'ИИ-ассистент временно недоступен. Попробуйте позже.'
export const FORBIDDEN_MESSAGE = 'У вас нет прав доступа к этому ресурсу.'

const minutesFrom = (data, fallback = 1) => Math.max(1, Math.ceil((Number(data?.retryAfterSeconds) || fallback * 60) / 60))
// For codes whose backend text is already Russian and specific (file names, limits): keep it, else a fallback.
const localized = (fallback) => (data) => (/[А-Яа-яЁё]/.test(data?.message ?? '') ? data.message : fallback)

/**
 * Russian text per backend error code (ErrorCodes.java). Codes are stable, messages are not, so this is consulted
 * first; a value is either the text or a function of the error body (for retry times and file names).
 */
export const CODE_MESSAGES = {
  // Authentication and accounts
  INVALID_CREDENTIALS: 'Неверный email или пароль. Проверьте данные или восстановите доступ.',
  EMAIL_TAKEN: 'Аккаунт с таким email уже существует',
  EMAIL_NOT_VERIFIED: localized('Email ещё не подтверждён. Введите код из письма или запросите новый.'),
  EMAIL_ALREADY_VERIFIED: localized('Email уже подтверждён. Войдите в систему.'),
  OTP_RESEND_COOLDOWN: (data) => `Повторно отправить код можно через ${Math.max(1, Number(data?.retryAfterSeconds) || 60)} с.`,
  TOO_MANY_LOGIN_ATTEMPTS: (data) => `Слишком много неудачных попыток входа. Попробуйте снова через ${minutesFrom(data, 15)} мин.`,
  LOGIN_USE_GOOGLE: 'Этот аккаунт зарегистрирован через Google. Войдите через Google.',
  ADMIN_ROLE_NOT_ALLOWED: 'Роль «Деканат» нельзя выбрать при самостоятельной регистрации. Обратитесь в деканат.',
  TEACHER_ROLE_PENDING: 'Заявка на роль преподавателя ещё не подтверждена деканатом. До подтверждения вы работаете как студент.',
  OTP_INVALID: 'Неверный или просроченный код',
  OTP_EXPIRED: 'Срок действия кода истёк. Запросите новый.',
  OTP_ATTEMPTS_EXCEEDED: 'Слишком много неудачных попыток. Запросите новый код.',
  PASSWORD_UNCHANGED: 'Новый пароль должен отличаться от текущего',
  ACCOUNT_NOT_FOUND: 'Аккаунт больше не существует. Войдите снова.',
  PHONE_INVALID: 'Телефон должен содержать от 10 до 15 цифр; можно использовать пробелы, дефисы, скобки и + в начале.',
  ACCOUNT_DISABLED: 'Аккаунт заблокирован.',
  AUTHENTICATION_FAILED: 'Не удалось войти. Попробуйте ещё раз.',
  AUTHENTICATION_REQUIRED: 'Сессия истекла. Войдите снова.',
  // Access
  ACCESS_DENIED: FORBIDDEN_MESSAGE,
  COURSE_NOT_ENROLLED: 'Вы не записаны на этот курс.',
  COURSE_NOT_MANAGED: 'Курс ведёт другой преподаватель. Вы можете управлять только своими курсами.',
  ADMIN_ONLY: 'Это действие доступно только деканату.',
  CANNOT_CHANGE_OWN_ROLE: 'Нельзя изменить собственную роль',
  CANNOT_DELETE_SELF: 'Нельзя удалить собственный аккаунт',
  // Not found
  USER_NOT_FOUND: 'Пользователь не найден',
  STUDENT_NOT_FOUND: 'Студент не найден',
  TEACHER_NOT_FOUND: 'Преподаватель не найден',
  TEACHER_REQUEST_NOT_FOUND: 'Заявка уже обработана или не найдена',
  COURSE_NOT_FOUND: 'Курс не найден',
  DOCUMENT_NOT_FOUND: 'Документ не найден',
  ASSIGNMENT_NOT_FOUND: 'Задание не найдено',
  ASSIGNMENT_HAS_ACTIVITY: localized('Студенты уже работают над этим заданием, поэтому удалить его нельзя. Закройте задание: оно останется в курсе вместе с решениями.'),
  ASSIGNMENT_CLOSED: 'Задание закрыто, новые решения не принимаются.',
  ASSIGNMENT_NOT_DRAFT: localized('Опубликовать можно только черновики этого курса. Обновите страницу.'),
  ASSIGNMENT_TITLE_REQUIRED: 'У черновика нет названия: без него задание нельзя опубликовать.',
  ASSIGNMENT_IS_DRAFT: localized('Это черновик: сначала опубликуйте его или удалите.'),
  OCR_UNAVAILABLE: 'Распознавание сканов не настроено на сервере. Обратитесь в деканат.',
  OCR_NOT_FAILED: 'Повторить можно только неудавшееся распознавание: сейчас оно не требуется или уже идёт.',
  DOCUMENT_BEING_PROCESSED: 'ИИ или распознавание ещё обрабатывают документ. Подождите, пока они закончат.',
  PII_ORIGINAL_RESTRICTED: localized('Оригинал доступен только преподавателю: в нём есть личные данные. Откройте вкладку «Текст» — там эти данные скрыты.'),
  TASK_NOT_FOUND: 'Задача не найдена',
  SUBMISSION_NOT_FOUND: 'Решение не найдено',
  ENDPOINT_NOT_FOUND: 'Запрошенный адрес не найден',
  // Courses
  COURSE_CREATE_FORBIDDEN: 'Создавать курсы могут только преподаватели и деканат.',
  COURSE_REQUIRED: 'Выберите курс: каждый документ принадлежит курсу',
  ENROLL_BY_LINK_ONLY: 'Записаться на курс можно только по ссылке-приглашению преподавателя.',
  INVITE_INVALID: 'Курс не найден. Ссылка неверна или больше не действует — попросите у преподавателя актуальную.',
  TOO_MANY_ATTEMPTS: (data) => `Слишком много попыток с неверным кодом. Попробуйте через ${minutesFrom(data, 10)} мин.`,
  NOT_A_STUDENT: 'Это действие доступно только студентам.',
  NOT_A_TEACHER: 'Пользователь не является преподавателем',
  // Tasks and submissions
  TASK_NOT_OWN: 'Можно менять только свои задачи',
  TASK_NOT_SUBMITTABLE: 'К этой задаче нельзя прикрепить решение.',
  // The server's text names the deadline.
  SUBMISSION_DEADLINE_PASSED: (data) => data?.message || 'Срок сдачи истёк, задание не принимает работы после срока.',
  SUBMISSION_ALREADY_GRADED: 'Работа уже оценена, сдать её заново нельзя.',
  TASK_NOT_GRADABLE: 'Оценить можно только задачу задания курса.',
  // The server's text names the allowed range.
  GRADE_SCORE_INVALID: (data) => data?.message || 'Недопустимая оценка.',
  NOTIFICATION_NOT_FOUND: 'Уведомление не найдено.',
  TASK_STATUS_SYSTEM_ONLY: 'Статусы «Сдано» и «Оценено» ставятся автоматически: сдайте файлы решения, а оценку выставит преподаватель.',
  // Uploads
  UPLOAD_FORBIDDEN: localized('Загружать документы курса может только его преподаватель или деканат.'),
  UPLOAD_EMPTY: localized('Файл пустой'),
  UPLOAD_TOO_MANY_FILES: localized('Слишком много файлов за один раз'),
  UPLOAD_UNSUPPORTED_TYPE: localized('Неподдерживаемый формат файла'),
  UPLOAD_TOO_LARGE: localized('Файл слишком большой (максимум 20 МБ)'),
  UPLOAD_CONTENT_MISMATCH: 'Содержимое файла не соответствует расширению',
  UPLOAD_INVALID_ARCHIVE: localized('Файл не является корректным ZIP-архивом'),
  PREVIEW_NOT_AVAILABLE: 'Предпросмотр недоступен для этого формата',
  // AI
  AI_NOT_CONFIGURED: 'ИИ-ассистент не подключён на сервере. Обратитесь в деканат.',
  AI_BUSY: 'ИИ-ассистент сейчас перегружен. Попробуйте через минуту.',
  AI_UNAVAILABLE: AI_UNAVAILABLE_MESSAGE,
  AI_QUEUE_FULL: 'Очередь ИИ-анализа заполнена. Попробуйте через минуту.',
  AI_NO_TEXT: 'В документе нет распознанного текста для анализа',
  AI_EXAM_ANSWERS_NOT_ANALYZED: 'Ответы к экзамену не анализируются на задания',
  // Requests
  VALIDATION_FAILED: 'Проверьте введённые данные',
  MALFORMED_REQUEST: 'Некорректные данные запроса',
  MISSING_PARAMETER: 'В запросе не хватает данных',
  INVALID_PARAMETER: 'Некорректное значение в запросе',
  METHOD_NOT_ALLOWED: 'Это действие не поддерживается',
  SEARCH_QUERY_INVALID: 'Поисковый запрос пуст или слишком длинный (до 200 символов)',
  SORT_NOT_ALLOWED: 'Нельзя отсортировать по этому полю',
  TOO_MANY_IDS: 'Слишком много документов в одном запросе',
  DATA_CONFLICT: 'Такие данные уже существуют',
  EMAIL_SEND_FAILED: 'Не удалось отправить письмо. Попробуйте позже.',
  INTERNAL_ERROR: 'Внутренняя ошибка сервера. Попробуйте позже.',
}

/** Text for an error body by its code, or null when the code is missing or unknown to this client. */
export function messageForCode(data) {
  const entry = data?.code ? CODE_MESSAGES[data.code] : undefined
  if (entry === undefined) return null
  return typeof entry === 'function' ? entry(data) : entry
}

/**
 * Message for any failed request. By code first; without a known code, 503 (AI off, down or overloaded) and 403 get
 * dedicated wording (an older backend may still send code-less errors).
 */
export function describeError(error) {
  const status = error?.response?.status
  const data = error?.response?.data
  const byCode = messageForCode(data)
  if (byCode) {
    return { status, message: byCode }
  }
  if (status === 503) {
    return { status, message: MESSAGE_TRANSLATIONS[data?.message] ?? AI_UNAVAILABLE_MESSAGE }
  }
  if (status === 403) {
    const known = MESSAGE_TRANSLATIONS[data?.message]
    return { status, message: known && known !== MESSAGE_TRANSLATIONS['Access denied'] ? known : FORBIDDEN_MESSAGE }
  }
  return { status, message: getErrorMessage(error) }
}

const FIELD_LABELS = {
  email: 'Email',
  password: 'Пароль',
  newPassword: 'Новый пароль',
  fullName: 'ФИО',
  otp: 'Код',
  role: 'Роль',
}

/**
 * Order: field errors (they name the fields), then the code (CODE_MESSAGES), then the old message translations
 * (kept for code-less responses), then the server's own text, then a generic fallback.
 */
export function getErrorMessage(error) {
  if (!error?.response) {
    return error?.code === 'ECONNABORTED'
      ? 'Сервер не ответил вовремя. Попробуйте ещё раз.'
      : 'Сервер недоступен. Проверьте, что бэкенд запущен.'
  }

  const { status, data } = error.response

  if (data?.fieldErrors) {
    return Object.entries(data.fieldErrors)
      .map(([field, message]) => `${FIELD_LABELS[field] ?? field}: ${message}`)
      .join('; ')
  }
  const byCode = messageForCode(data)
  if (byCode) {
    return byCode
  }
  if (data?.message) {
    return MESSAGE_TRANSLATIONS[data.message] ?? data.message
  }
  if (status >= 500) {
    return 'Внутренняя ошибка сервера. Попробуйте позже.'
  }
  return 'Что-то пошло не так. Попробуйте ещё раз.'
}

// With responseType "blob" the error body is a Blob too; parse it so getErrorMessage sees the JSON message.
export async function getBlobErrorMessage(error) {
  const data = error.response?.data
  if (data instanceof Blob) {
    try {
      error.response.data = JSON.parse(await data.text())
    } catch {
      error.response.data = null
    }
  }
  return getErrorMessage(error)
}

export default api
