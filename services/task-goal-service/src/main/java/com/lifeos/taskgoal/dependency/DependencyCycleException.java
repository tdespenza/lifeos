package com.lifeos.taskgoal.dependency;

/** A requested edge would make the persisted owner-scoped graph cyclic. */
public class DependencyCycleException extends RuntimeException {
}
