package com.lifeos.calendar.service;

/** Task/Goal links fail closed whenever the authenticated TaskGoal projection is unavailable. */
public class UnsupportedCalendarLinkException extends RuntimeException {
}
