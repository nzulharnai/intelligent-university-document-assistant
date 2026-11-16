import AiChat from './AiChat'

const SUGGESTIONS = ['Когда экзамен?', 'Какие лабораторные работы нужно сдать?', 'Как выставляется итоговая оценка?']

// Answers come from all course documents the user may see; each source names its document. The questions are saved
// (POST /ai/ask with the course) and appear in "История" of the AI assistant page.
export default function CourseChat({ course }) {
  return (
    <AiChat
      endpoint="/ai/ask"
      courseId={course.id}
      title="Задать вопрос по курсу"
      subtitle={`Поиск по всем доступным вам документам курса «${course.title}»`}
      placeholder="Например: какие темы будут на экзамене?"
      suggestions={SUGGESTIONS}
      showDocuments
    />
  )
}
