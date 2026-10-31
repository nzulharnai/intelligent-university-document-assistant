package com.university.docassistant.service;

import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.AnalysisSource;
import com.university.docassistant.service.AiTaskExtractionService.Analysis;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EXAM_ANSWERS are skipped even when the analysis is invoked directly, bypassing DocumentService's 400; a partial
 * analysis (an AI chunk failed) is stored as such and keeps the assignments it did not find.
 */
class DocumentAnalysisServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final AssignmentService assignments = mock(AssignmentService.class);
    private final AiTaskExtractionService extraction = mock(AiTaskExtractionService.class);
    private final DocumentAnalysisService service = new DocumentAnalysisService(documents, assignments, extraction,
            mock(PlatformTransactionManager.class));

    @Test
    void manualReanalysisOfExamAnswersNeverCallsTheAiOrCreatesAssignments() {
        when(documents.findAnalysisSource(7L)).thenReturn(Optional.of(
                new AnalysisSource(7L, DocumentCategory.EXAM_ANSWERS, "Задание 1: ответ ... срок 15.12", 1L, 10L)));

        service.reanalyzeAsync(7L); // Called directly: no Spring proxy, so it runs synchronously.

        verify(extraction, never()).analyze(anyString());
        verifyNoInteractions(assignments);
        verify(documents, never()).lockById(any()); // Nothing is written either.
    }

    @Test
    void partialAnalysisSavesItsDraftsAndRecordsThatItWasPartial() {
        List<TaskExtractDto> found = List.of(new TaskExtractDto("Lab 1", null, null));
        syllabusWith(new Analysis("Summary", List.of("Sorting"), found, true));

        service.analyzeNow(7L, true);

        verify(documents).updateAnalysis(eq(7L), eq("Summary"), eq("Sorting"), eq(true), any());
        verify(assignments).replaceFromDocument(7L, 10L, found, false);
    }

    @Test
    void completeAnalysisClearsThePartialMark() {
        List<TaskExtractDto> found = List.of(new TaskExtractDto("Lab 1", null, null));
        syllabusWith(new Analysis("Summary", List.of(), found));

        service.analyzeNow(7L, true);

        verify(documents).updateAnalysis(eq(7L), eq("Summary"), eq(null), eq(false), any());
        verify(assignments).replaceFromDocument(7L, 10L, found, true);
    }

    private void syllabusWith(Analysis analysis) {
        when(documents.findAnalysisSource(7L)).thenReturn(Optional.of(
                new AnalysisSource(7L, DocumentCategory.SYLLABUS, "Lab 1 ... due 15.12", 1L, 10L)));
        when(documents.lockById(7L)).thenReturn(Optional.of(7L));
        when(extraction.analyze(anyString())).thenReturn(Optional.of(analysis));
    }
}
