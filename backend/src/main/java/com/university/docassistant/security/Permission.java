package com.university.docassistant.security;

import com.university.docassistant.entity.Role;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What a role may do at all. Whether it may do it to a given course or document is decided by
 * {@link AccessControlService} from the user's relation to the course (enrolled student, its teacher, ADMIN).
 */
public enum Permission {
    VIEW_COURSE,
    MANAGE_COURSE,
    VIEW_DOCUMENT,
    UPLOAD_DOCUMENT,
    DELETE_DOCUMENT,
    ANALYZE_DOCUMENT,
    MANAGE_ASSIGNMENTS,
    ASK_AI,
    /** Hand in files for one's own task: the only kind of upload a STUDENT may make. */
    SUBMIT_TASK,
    /** Grade students' work and give feedback: the course's teacher or ADMIN (US-11). */
    GRADE_WORK,
    MANAGE_SYSTEM;

    private static final Map<Role, Set<Permission>> BY_ROLE = Map.of(
            Role.ROLE_STUDENT, EnumSet.of(VIEW_COURSE, VIEW_DOCUMENT, ASK_AI, SUBMIT_TASK),
            Role.ROLE_TEACHER, EnumSet.complementOf(EnumSet.of(MANAGE_SYSTEM, SUBMIT_TASK)),
            Role.ROLE_ADMIN, EnumSet.complementOf(EnumSet.of(SUBMIT_TASK)));

    public static boolean granted(Role role, Permission permission) {
        return role != null && BY_ROLE.get(role).contains(permission);
    }
}
