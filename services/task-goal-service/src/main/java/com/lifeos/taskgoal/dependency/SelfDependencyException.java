package com.lifeos.taskgoal.dependency;

/** A dependency cannot point from a persisted node to itself. */
public class SelfDependencyException extends RuntimeException {
}
