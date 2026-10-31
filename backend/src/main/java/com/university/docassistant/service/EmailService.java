package com.university.docassistant.service;

import com.university.docassistant.exception.EmailSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmailService {

    private static final String OTP_DIGIT_STYLE = "display:inline-block;width:44px;height:56px;line-height:56px;"
            + "margin:0 4px;border-radius:10px;background-color:#eef2ff;border:1px solid #c7d2fe;"
            + "color:#3730a3;font-size:28px;font-weight:700;font-family:Consolas,Menlo,monospace;text-align:center;";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final long otpExpirationMinutes;

    public EmailService(JavaMailSender mailSender,
                        @Value("${app.mail.from}") String fromAddress,
                        @Value("${app.mail.from-name}") String fromName,
                        @Value("${app.otp.expiration-minutes}") long otpExpirationMinutes) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.otpExpirationMinutes = otpExpirationMinutes;
    }

    public void sendEmailVerificationOtp(String to, String fullName, String otp) {
        String html = buildOtpHtml(fullName, to,
                "Подтвердите ваш email",
                "Спасибо за регистрацию в UniDocs Assistant! Чтобы завершить регистрацию, "
                        + "введите этот код на странице подтверждения:",
                otp,
                "Если вы не регистрировались в UniDocs Assistant, просто проигнорируйте это письмо.");
        String plainText = "Ваш код подтверждения email: " + otp
                + ". Код действует " + otpExpirationMinutes + " минут.";

        send(to, "Подтверждение email — UniDocs Assistant", html, plainText);
    }

    public void sendPasswordResetOtp(String to, String fullName, String otp) {
        String html = buildOtpHtml(fullName, to,
                "Восстановление пароля",
                "Мы получили запрос на сброс пароля для вашего аккаунта. Введите этот код, "
                        + "чтобы задать новый пароль:",
                otp,
                "Если вы не запрашивали сброс пароля, просто проигнорируйте это письмо — ваш пароль не изменится.");
        String plainText = "Ваш код для сброса пароля: " + otp
                + ". Код действует " + otpExpirationMinutes + " минут.";

        send(to, "Восстановление пароля — UniDocs Assistant", html, plainText);
    }

    /**
     * A notification (US-13): its title and text, a button to {@code url} in the site, and how to turn such e-mails
     * off. Throws EmailSendException; NotificationService logs it, the action that caused it has already committed.
     */
    public void sendNotification(String to, String fullName, String title, String body, String url) {
        String displayName = HtmlUtils.htmlEscape(fullName != null && !fullName.isBlank() ? fullName : to);
        String bodyHtml = body == null ? "" : HtmlUtils.htmlEscape(body).replace("\n", "<br>");
        String html = """
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <style>
                    @media only screen and (max-width: 480px) {
                      .card-body { padding: 24px 16px !important; }
                      .card-header { padding: 22px 16px !important; }
                    }
                  </style>
                </head>
                <body style="margin:0;padding:0;background-color:#f6f9fc;font-family:'Segoe UI',Roboto,Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f6f9fc;padding:32px 8px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                               style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(15,35,61,0.08);">
                          <tr>
                            <td class="card-header" style="background-color:#1d6fe0;padding:28px 32px;">
                              <div style="color:#ffffff;font-size:20px;font-weight:700;">&#128196; UniDocs Assistant</div>
                              <div style="color:#eaf2fd;font-size:13px;margin-top:4px;">Уведомление</div>
                            </td>
                          </tr>
                          <tr>
                            <td class="card-body" style="padding:32px;">
                              <h1 style="margin:0 0 16px;color:#0f233d;font-size:22px;">%s</h1>
                              <p style="margin:0 0 8px;color:#4b5f77;font-size:15px;line-height:1.6;">Здравствуйте, <b>%s</b>!</p>
                              <p style="margin:0 0 24px;color:#4b5f77;font-size:15px;line-height:1.6;">%s</p>
                              <div style="text-align:center;margin:0 0 24px;">
                                <a href="%s" style="display:inline-block;padding:12px 24px;border-radius:10px;background-color:#1d6fe0;color:#ffffff;font-size:15px;font-weight:600;text-decoration:none;">Открыть в UniDocs</a>
                              </div>
                              <hr style="border:none;border-top:1px solid #dce6f0;margin:0 0 16px;">
                              <p style="margin:0;color:#5b6e84;font-size:12px;line-height:1.6;">Отключить письма можно в профиле.</p>
                            </td>
                          </tr>
                        </table>
                        <p style="color:#5b6e84;font-size:12px;margin-top:16px;">Это автоматическое письмо, отвечать на него не нужно.</p>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(title), displayName, bodyHtml, HtmlUtils.htmlEscape(url));
        String plainText = title + "\n\n" + (body == null ? "" : body + "\n\n") + "Открыть: " + url
                + "\n\nОтключить письма можно в профиле.";
        send(to, title + " — UniDocs Assistant", html, plainText);
    }

    private void send(String to, String subject, String html, String plainText) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plainText, html);

            mailSender.send(message);
            log.info("Email '{}' sent to {}", subject, to);
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            throw new EmailSendException("Failed to send '" + subject + "' to " + to, ex);
        }
    }

    private String buildOtpHtml(String fullName, String email, String title, String intro,
                                String otp, String footerNote) {
        String displayName = HtmlUtils.htmlEscape(
                fullName != null && !fullName.isBlank() ? fullName : email);
        String otpDigits = otp.chars()
                .mapToObj(digit -> "<span class=\"otp-digit\" style=\"" + OTP_DIGIT_STYLE + "\">"
                        + (char) digit + "</span>")
                .collect(Collectors.joining());

        return """
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <style>
                    @media only screen and (max-width: 480px) {
                      .card-body { padding: 24px 16px !important; }
                      .card-header { padding: 22px 16px !important; }
                      .otp-digit {
                        width: 36px !important; height: 48px !important; line-height: 48px !important;
                        font-size: 22px !important; margin: 0 2px !important;
                      }
                    }
                  </style>
                </head>
                <body style="margin:0;padding:0;background-color:#f1f5f9;font-family:'Segoe UI',Roboto,Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f1f5f9;padding:32px 8px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                               style="max-width:520px;background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(15,23,42,0.08);">
                          <tr>
                            <td class="card-header" style="background-color:#4f46e5;background-image:linear-gradient(135deg,#4f46e5,#7c3aed);padding:28px 32px;">
                              <div style="color:#ffffff;font-size:20px;font-weight:700;">&#128196; UniDocs Assistant</div>
                              <div style="color:#e0e7ff;font-size:13px;margin-top:4px;">Интеллектуальный помощник по университетским документам</div>
                            </td>
                          </tr>
                          <tr>
                            <td class="card-body" style="padding:32px;">
                              <h1 style="margin:0 0 16px;color:#0f172a;font-size:22px;">%s</h1>
                              <p style="margin:0 0 8px;color:#334155;font-size:15px;line-height:1.6;">Здравствуйте, <b>%s</b>!</p>
                              <p style="margin:0 0 24px;color:#334155;font-size:15px;line-height:1.6;">%s</p>
                              <div style="text-align:center;margin:0 0 24px;white-space:nowrap;">%s</div>
                              <p style="margin:0 0 24px;text-align:center;color:#64748b;font-size:13px;">
                                Код действителен <b>%d минут</b>. Никому не сообщайте его.
                              </p>
                              <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 16px;">
                              <p style="margin:0;color:#94a3b8;font-size:12px;line-height:1.6;">%s</p>
                            </td>
                          </tr>
                        </table>
                        <p style="color:#94a3b8;font-size:12px;margin-top:16px;">Это автоматическое письмо, отвечать на него не нужно.</p>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(title, displayName, intro, otpDigits, otpExpirationMinutes, footerNote);
    }
}
