package com.myplus.auth.exception;

/** A sign-in refused for a {@link SignInRefusal} reason — answered with that reason's code and status. */
public class SignInRefusedException extends RuntimeException {

    private final SignInRefusal refusal;

    public SignInRefusedException(SignInRefusal refusal) {
        super(refusal.defaultMessage());
        this.refusal = refusal;
    }

    public SignInRefusal refusal() { return refusal; }
}
