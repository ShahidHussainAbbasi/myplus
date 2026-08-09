package com.myplus.notification.entity;

/**
 * How a notification is delivered. Slice 105 (D4) — a PORT, not a switch statement.
 *
 * <p>EMAIL ships; SMS is defined so the model is honest about what the platform intends, and deliberately
 * NOT implemented: there is no SMS provider decision yet, and inventing one to fill an enum would be a
 * capability nobody asked for. A delivery row carrying SMS today would simply never be dispatched.
 */
public enum Channel { EMAIL, SMS }
