package com.security;

import org.springframework.security.core.AuthenticationException;

/** A failed sign-in with its {@link SignInFailure} — the failure handler turns that into the page's message. */
public class SignInFailedException extends AuthenticationException {

    private final transient SignInFailure failure;

    public SignInFailedException(SignInFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public SignInFailedException(SignInFailure failure, Throwable cause) {
        super(failure.name(), cause);
        this.failure = failure;
    }

    public SignInFailure failure() { return failure; }
}
