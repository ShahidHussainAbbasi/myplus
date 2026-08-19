package com.myplus.common.imports;

/**
 * What the engine decided about one row, and why.
 *
 * <p>The three statuses are not three severities of the same thing — they are three different facts, and the
 * preview reports them differently (slice §11):
 *
 * <ul>
 *   <li>{@link Status#ERROR} — <b>a call to action.</b> The operator must edit the file and re-upload. Always
 *       listed in full, with the row number and the reason.</li>
 *   <li>{@link Status#SKIP} — <b>not</b> a call to action. The row already exists, which is the outcome the
 *       operator wanted. Collapsed to a count, expandable.</li>
 *   <li>{@link Status#CREATE} — will be inserted on commit.</li>
 * </ul>
 */
public final class RowResult {

    public enum Status { CREATE, SKIP, ERROR }

    private final int rowNumber;
    private final Status status;
    private final String message;

    private RowResult(int rowNumber, Status status, String message) {
        this.rowNumber = rowNumber;
        this.status = status;
        this.message = message;
    }

    public static RowResult create(int rowNumber) {
        return new RowResult(rowNumber, Status.CREATE, null);
    }

    public static RowResult skip(int rowNumber, String message) {
        return new RowResult(rowNumber, Status.SKIP, message);
    }

    public static RowResult error(int rowNumber, String message) {
        return new RowResult(rowNumber, Status.ERROR, message);
    }

    /** 1-based line number as the operator's spreadsheet shows it, header included. */
    public int getRowNumber() { return rowNumber; }

    /** Serialised as the enum NAME so the browser can branch on it without translating. */
    public Status getStatus() { return status; }

    public String getMessage() { return message; }
}
