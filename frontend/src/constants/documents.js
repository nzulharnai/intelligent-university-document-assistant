// Same names as the backend DocumentVisibility enum. Every document belongs to a course; PUBLIC_ALL is a legacy value
// that the backend now treats like COURSE_STUDENTS.
export const VISIBILITY_LABELS = {
  PUBLIC_ALL: 'Студенты курса',
  COURSE_STUDENTS: 'Студенты курса',
  TEACHER_ONLY: 'Только преподаватель курса',
}

// Same names as the backend DocumentCategory enum. TRANSCRIPT and THESIS are legacy: kept so older documents still
// show a label, but no longer offered on upload.
export const CATEGORY_LABELS = {
  SYLLABUS: 'Силлабус',
  ASSIGNMENT: 'Задание',
  LECTURE: 'Лекция',
  EXAM_ANSWERS: 'Ответы к экзамену',
  REGULATION: 'Положение / регламент',
  TRANSCRIPT: 'Транскрипт',
  THESIS: 'Диссертация / ВКР',
  OTHER: 'Другое',
}

// The six Sprint 2 categories (DocumentClassificationService.TYPES): what the upload select offers.
const SPEC_CATEGORIES = ['SYLLABUS', 'ASSIGNMENT', 'LECTURE', 'EXAM_ANSWERS', 'REGULATION', 'OTHER']

export const CATEGORY_OPTIONS = SPEC_CATEGORIES.map((value) => ({ value, label: CATEGORY_LABELS[value] }))

// The backend always stores this category as TEACHER_ONLY and never analyzes it into assignments.
export const TEACHER_ONLY_CATEGORY = 'EXAM_ANSWERS'

// Formats the backend accepts in multi-file and ZIP uploads (DocumentService.ALLOWED_TYPES).
export const DOCUMENT_EXTENSIONS = ['pdf', 'docx', 'doc', 'pptx', 'txt', 'png', 'jpg', 'jpeg']

// Categories the backend analyzes with AI right after upload (DocumentAnalysisService.AUTO_ANALYZED).
export const AUTO_ANALYZED_CATEGORIES = new Set(['SYLLABUS', 'ASSIGNMENT'])

/** What the course teacher (or ADMIN) can choose when uploading. */
export const VISIBILITY_OPTIONS = [
  { value: 'COURSE_STUDENTS', label: 'Студенты курса' },
  { value: 'TEACHER_ONLY', label: 'Только преподаватель курса (заметки)' },
]

// Same names as the backend TaskStatus enum. SUBMITTED and GRADED are set by the system (a submission, the
// teacher's grade); COMPLETED exists only on legacy tasks without an assignment.
export const TASK_STATUS_LABELS = {
  PENDING: 'Не начато',
  IN_PROGRESS: 'В работе',
  SUBMITTED: 'Сдано',
  GRADED: 'Оценено',
  COMPLETED: 'Выполнено',
}

// What the student may pick by hand: on an assignment task only these two, on a legacy task also COMPLETED.
export const ASSIGNMENT_TASK_STATUSES = ['PENDING', 'IN_PROGRESS']
export const LEGACY_TASK_STATUSES = ['PENDING', 'IN_PROGRESS', 'COMPLETED']
