package com.apex.commerce.mail;

/** Account emails (password reset link, password changed notice), sent after the transaction commits. */
@io.quarkus.qute.TemplateData
public record AccountEmail(Kind kind, String to, String fullName, String storeName, String resetUrl, long validMinutes) {

    public enum Kind { PASSWORD_RESET, PASSWORD_CHANGED }
}
