package com.firstclub.membership.exception;

/** Maps to HTTP 409. Used for state conflicts (e.g. an already-active subscription). */
public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
