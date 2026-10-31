package com.university.docassistant.exception;

/**
 * Machine-readable error codes returned in {@link ErrorResponse#code()}. Every error a user can see carries one, so
 * clients translate and react by code instead of by message text (messages may change, codes do not).
 * ApiException cannot be created without a code.
 */
public final class ErrorCodes {

    // --- Authentication and accounts --------------------------------------------------------------------------------
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String EMAIL_ALREADY_VERIFIED = "EMAIL_ALREADY_VERIFIED";
    public static final String OTP_RESEND_COOLDOWN = "OTP_RESEND_COOLDOWN";
    /** Registration with an email that already has an account; the form highlights the email field. */
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
    /** Wrong email or password at login; deliberately the same for both, see AuthService.login. */
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    /** 429 from login; Retry-After / retryAfterSeconds say when the (email, IP) pair or the IP is unblocked. */
    public static final String TOO_MANY_LOGIN_ATTEMPTS = "TOO_MANY_LOGIN_ATTEMPTS";
    public static final String LOGIN_USE_GOOGLE = "LOGIN_USE_GOOGLE";
    public static final String ADMIN_ROLE_NOT_ALLOWED = "ADMIN_ROLE_NOT_ALLOWED";
    /** A student whose teacher role request ADMIN has not approved yet tried a teacher action. */
    public static final String TEACHER_ROLE_PENDING = "TEACHER_ROLE_PENDING";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String OTP_ATTEMPTS_EXCEEDED = "OTP_ATTEMPTS_EXCEEDED";
    public static final String PASSWORD_UNCHANGED = "PASSWORD_UNCHANGED";
    /** The user behind the token no longer exists. */
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    /** PATCH /users/me with a phone that is not 10-15 digits (spaces, dashes, brackets and a leading + allowed). */
    public static final String PHONE_INVALID = "PHONE_INVALID";
    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    /** No token, or an invalid or outdated one (e.g. the role changed); the client should log in again. */
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";

    // --- Access -----------------------------------------------------------------------------------------------------
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String COURSE_NOT_ENROLLED = "COURSE_NOT_ENROLLED";
    public static final String COURSE_NOT_MANAGED = "COURSE_NOT_MANAGED";
    public static final String ADMIN_ONLY = "ADMIN_ONLY";
    public static final String CANNOT_CHANGE_OWN_ROLE = "CANNOT_CHANGE_OWN_ROLE";
    public static final String CANNOT_DELETE_SELF = "CANNOT_DELETE_SELF";

    // --- Not found --------------------------------------------------------------------------------------------------
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String STUDENT_NOT_FOUND = "STUDENT_NOT_FOUND";
    public static final String TEACHER_NOT_FOUND = "TEACHER_NOT_FOUND";
    public static final String TEACHER_REQUEST_NOT_FOUND = "TEACHER_REQUEST_NOT_FOUND";
    public static final String COURSE_NOT_FOUND = "COURSE_NOT_FOUND";
    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";
    public static final String ASSIGNMENT_NOT_FOUND = "ASSIGNMENT_NOT_FOUND";
    /** 409: the assignment has student work (submissions or changed statuses); close it instead of deleting. */
    public static final String ASSIGNMENT_HAS_ACTIVITY = "ASSIGNMENT_HAS_ACTIVITY";
    /** 409: the assignment is closed and takes no more submissions. */
    public static final String ASSIGNMENT_CLOSED = "ASSIGNMENT_CLOSED";
    /** 400: publishing something that is not a DRAFT of this course (unknown id, other course, already published). */
    public static final String ASSIGNMENT_NOT_DRAFT = "ASSIGNMENT_NOT_DRAFT";
    /** 400: a draft without a title cannot be published. */
    public static final String ASSIGNMENT_TITLE_REQUIRED = "ASSIGNMENT_TITLE_REQUIRED";
    /** 400: a DRAFT cannot be closed (that would show it to students); publish or delete it. */
    public static final String ASSIGNMENT_IS_DRAFT = "ASSIGNMENT_IS_DRAFT";
    /** 403: the original file contains personal data and the course teacher has not opened it to students. */
    public static final String PII_ORIGINAL_RESTRICTED = "PII_ORIGINAL_RESTRICTED";
    /** 503: OCR is switched off or Tesseract is missing on the server (US-07). */
    public static final String OCR_UNAVAILABLE = "OCR_UNAVAILABLE";
    /** 409: only a failed text recognition can be retried. */
    public static final String OCR_NOT_FAILED = "OCR_NOT_FAILED";
    /** 409: the AI or OCR is still working on the document; wait until it finishes. */
    public static final String DOCUMENT_BEING_PROCESSED = "DOCUMENT_BEING_PROCESSED";
    public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";
    public static final String SUBMISSION_NOT_FOUND = "SUBMISSION_NOT_FOUND";
    public static final String ENDPOINT_NOT_FOUND = "ENDPOINT_NOT_FOUND";

    // --- Courses ----------------------------------------------------------------------------------------------------
    public static final String COURSE_CREATE_FORBIDDEN = "COURSE_CREATE_FORBIDDEN";
    public static final String COURSE_REQUIRED = "COURSE_REQUIRED";
    /** Students join only through the invitation link, never by course id. */
    public static final String ENROLL_BY_LINK_ONLY = "ENROLL_BY_LINK_ONLY";
    /** Unknown or replaced invitation code (404, says nothing about which courses exist). */
    public static final String INVITE_INVALID = "INVITE_INVALID";
    /** 429 after too many wrong invitation codes; retryAfterSeconds says when to try again. */
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";
    public static final String NOT_A_STUDENT = "NOT_A_STUDENT";
    public static final String NOT_A_TEACHER = "NOT_A_TEACHER";

    // --- Tasks and submissions --------------------------------------------------------------------------------------
    public static final String TASK_NOT_OWN = "TASK_NOT_OWN";
    public static final String TASK_NOT_SUBMITTABLE = "TASK_NOT_SUBMITTABLE";
    /** A student tried to set SUBMITTED, GRADED or (on an assignment task) COMPLETED, or to leave SUBMITTED/GRADED. */
    public static final String TASK_STATUS_SYSTEM_ONLY = "TASK_STATUS_SYSTEM_ONLY";
    /** A hand-in after the deadline of an assignment that does not accept late work; the message names the deadline. */
    public static final String SUBMISSION_DEADLINE_PASSED = "SUBMISSION_DEADLINE_PASSED";
    /** A hand-in for a task the teacher has already graded. */
    public static final String SUBMISSION_ALREADY_GRADED = "SUBMISSION_ALREADY_GRADED";
    /** Only tasks of a course assignment are graded (not legacy tasks). */
    public static final String TASK_NOT_GRADABLE = "TASK_NOT_GRADABLE";
    /** Score outside 0..max_score (0..1000 without one) or with more than one decimal; the message names the range. */
    public static final String GRADE_SCORE_INVALID = "GRADE_SCORE_INVALID";

    // --- Notifications ----------------------------------------------------------------------------------------------
    /** Not one of the user's own notifications (also for others' ids, which reveal nothing). */
    public static final String NOTIFICATION_NOT_FOUND = "NOTIFICATION_NOT_FOUND";

    // --- Uploads ----------------------------------------------------------------------------------------------------
    public static final String UPLOAD_FORBIDDEN = "UPLOAD_FORBIDDEN";
    public static final String UPLOAD_EMPTY = "UPLOAD_EMPTY";
    public static final String UPLOAD_TOO_MANY_FILES = "UPLOAD_TOO_MANY_FILES";
    public static final String UPLOAD_UNSUPPORTED_TYPE = "UPLOAD_UNSUPPORTED_TYPE";
    public static final String UPLOAD_TOO_LARGE = "UPLOAD_TOO_LARGE";
    /** The file's bytes are not what its extension says (FileTypeValidator). */
    public static final String UPLOAD_CONTENT_MISMATCH = "UPLOAD_CONTENT_MISMATCH";
    public static final String UPLOAD_INVALID_ARCHIVE = "UPLOAD_INVALID_ARCHIVE";
    public static final String PREVIEW_NOT_AVAILABLE = "PREVIEW_NOT_AVAILABLE";

    // --- AI ---------------------------------------------------------------------------------------------------------
    public static final String AI_NOT_CONFIGURED = "AI_NOT_CONFIGURED";
    /** Too many questions in flight; try again shortly. */
    public static final String AI_BUSY = "AI_BUSY";
    /** The provider failed or gave no usable answer. */
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String AI_QUEUE_FULL = "AI_QUEUE_FULL";
    public static final String AI_NO_TEXT = "AI_NO_TEXT";
    public static final String AI_EXAM_ANSWERS_NOT_ANALYZED = "AI_EXAM_ANSWERS_NOT_ANALYZED";

    // --- Requests ---------------------------------------------------------------------------------------------------
    /** Some fields failed validation; {@link ErrorResponse#fieldErrors()} says which. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String MISSING_PARAMETER = "MISSING_PARAMETER";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String SEARCH_QUERY_INVALID = "SEARCH_QUERY_INVALID";
    public static final String SORT_NOT_ALLOWED = "SORT_NOT_ALLOWED";
    /** A batch request names more ids than allowed (e.g. GET /documents/status, at most 50). */
    public static final String TOO_MANY_IDS = "TOO_MANY_IDS";
    public static final String DATA_CONFLICT = "DATA_CONFLICT";
    public static final String EMAIL_SEND_FAILED = "EMAIL_SEND_FAILED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
