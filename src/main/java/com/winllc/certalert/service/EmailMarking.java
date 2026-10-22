package com.winllc.certalert.service;

import com.winllc.certalert.config.BannerProperties;
import com.winllc.certalert.license.Licensing;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * The classification marking on email, where the pages carry one: in the subject, and at the
 * top and the bottom of the text and the HTML - the same marking the banners show, applied to
 * every message as it is queued (round-ups, alerts, scheduled reports alike), so no kind of
 * message can be sent without it.
 */
@Component
public class EmailMarking {

    private final BannerProperties banner;
    private final ObjectProvider<Licensing> licensing;

    public EmailMarking(BannerProperties banner, ObjectProvider<Licensing> licensing) {
        this.banner = banner;
        this.licensing = licensing;
    }

    /** The marking, or null where the banners are off or not licensed. */
    public String marking() {
        Licensing check = licensing.getIfAvailable();
        return banner.isVisible() && check != null && check.shows("classification-banners") ? banner.getText().trim() : null;
    }

    public String subject(String subject, String marking) {
        String prefix = "[" + marking + "] ";
        return subject == null ? prefix.trim() : subject.startsWith(prefix) ? subject : prefix + subject;
    }

    public String text(String body, String marking) {
        return marking + "\n\n" + (body == null ? "" : body) + "\n\n" + marking + "\n";
    }

    public String html(String body, String marking) {
        if (body == null) {
            return null;
        }
        String bar = "<div style=\"background:" + banner.getBackground() + ";color:" + banner.getTextColor()
                + ";text-align:center;font:600 12px Arial,sans-serif;letter-spacing:.06em;padding:4px;"
                + "text-transform:uppercase;\">" + HtmlUtils.htmlEscape(marking) + "</div>";
        int open = body.toLowerCase(java.util.Locale.ROOT).indexOf("<body");
        int openEnd = open < 0 ? -1 : body.indexOf('>', open);
        int close = body.toLowerCase(java.util.Locale.ROOT).lastIndexOf("</body>");
        if (openEnd < 0 || close < openEnd) {
            return bar + body + bar;
        }
        return body.substring(0, openEnd + 1) + bar + body.substring(openEnd + 1, close) + bar + body.substring(close);
    }
}
