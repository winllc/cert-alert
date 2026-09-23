package com.winllc.certalert.config;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The fixed bars across the top and bottom of every page, bound from
 * {@code cert-alert.banner}.
 *
 * <p>What a deployment inside an accredited network is normally required to display: the
 * classification of what is on the screen, at the top and the bottom, where a screenshot or
 * a photograph of the monitor cannot be taken without it. Off by default, because an
 * application that invents its own classification marking is worse than one that shows
 * none.
 *
 * <p>Registered as a {@code @Component} rather than left to the properties scan so that it
 * has a name a template can ask for: every page reads it as {@code @bannerProperties}.
 *
 * <p>The bars are drawn by the head fragment, which is the one thing all twelve pages
 * include - there is no decorator layout here, only a bag of named fragments, and a
 * {@code <head>} cannot hold body content. So they are {@code body::before} and
 * {@code body::after} rather than elements, which puts them on every page from one place
 * and leaves nothing for a page added later to forget.
 *
 * <p>The colours and the height are written into a stylesheet, so they are checked against
 * what a colour and a length may look like rather than passed through. An operator typing
 * a stray brace into a properties file should get the default back, not a broken page.
 */
@Component
@ConfigurationProperties(prefix = "cert-alert.banner")
public class BannerProperties {

    /** {@code #abc}, {@code #aabbcc}, or a CSS colour keyword. */
    private static final Pattern COLOUR = Pattern.compile("^(#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}|[a-zA-Z]{3,20})$");

    /** A number and a unit CSS understands for a height. */
    private static final Pattern LENGTH = Pattern.compile("^(?:\\d{1,4}|\\d{0,4}\\.\\d{1,3})(px|rem|em|pt|vh)$");

    static final String DEFAULT_TEXT_COLOR = "#ffffff";
    static final String DEFAULT_BACKGROUND = "#1d273b";
    static final String DEFAULT_HEIGHT = "1.5rem";

    /** Whether the bars are shown at all. */
    private boolean enabled = false;

    /** What they say - a classification marking, normally. Escaped when rendered. */
    private String text = "";

    private String textColor = DEFAULT_TEXT_COLOR;

    private String background = DEFAULT_BACKGROUND;

    /** How tall each bar is. The page is padded by this much at each end to clear them. */
    private String height = DEFAULT_HEIGHT;

    /**
     * Shown only when it is switched on and has something to say. A bar with no text is a
     * coloured stripe that pushes the page down for no reason.
     */
    public boolean isVisible() {
        return enabled && text != null && !text.isBlank();
    }

    public String getText() {
        return text == null ? "" : text.trim();
    }

    /** The colour as it will be written into the stylesheet, or the default if it is not one. */
    public String getTextColor() {
        return colourOr(textColor, DEFAULT_TEXT_COLOR);
    }

    public String getBackground() {
        return colourOr(background, DEFAULT_BACKGROUND);
    }

    public String getHeight() {
        String trimmed = height == null ? "" : height.trim();
        return LENGTH.matcher(trimmed).matches() ? trimmed.toLowerCase(Locale.ROOT) : DEFAULT_HEIGHT;
    }

    /**
     * The text as a quoted CSS string, for {@code content:}.
     *
     * <p>Escaped here because this is the one value that cannot be checked against a
     * pattern - a marking is whatever the deployment says it is. Two things have to be
     * impossible: ending the string early, and ending the {@code <style>} element. The
     * first is why a quote and a backslash are escaped; the second is why an angle bracket
     * becomes a CSS code point rather than staying itself, since an HTML parser reading
     * {@code </style>} inside a stylesheet stops reading a stylesheet.
     */
    public String getCssText() {
        StringBuilder css = new StringBuilder("\"");
        for (char c : getText().toCharArray()) {
            if (c == '"' || c == '\\') {
                css.append('\\').append(c);
            } else if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                // \3c and friends. The trailing space is what ends a CSS code point escape.
                css.append('\\').append(Integer.toHexString(c)).append(' ');
            } else {
                css.append(c);
            }
        }
        return css.append('"').toString();
    }

    private static String colourOr(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return COLOUR.matcher(trimmed).matches() ? trimmed : fallback;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setTextColor(String textColor) {
        this.textColor = textColor;
    }

    public void setBackground(String background) {
        this.background = background;
    }

    public void setHeight(String height) {
        this.height = height;
    }
}
