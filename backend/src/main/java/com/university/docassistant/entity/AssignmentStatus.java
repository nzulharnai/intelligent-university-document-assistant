package com.university.docassistant.entity;

/**
 * ACTIVE assignments are handed out to the course's students; CLOSED ones stay for history (with their students'
 * work) and take no more submissions. What the AI finds is ACTIVE at once.
 */
public enum AssignmentStatus {
    ACTIVE,
    CLOSED
}
