package com.winllc.certalert.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The notice-and-consent banner (DISA STIG): what it says, and whether this browser session
 * has agreed to it.
 *
 * <p>Enforced on the server rather than drawn by the page. While it is on, a session that has
 * not pressed "I agree" is sent to the banner from everywhere but the banner itself, its
 * assets and the machine endpoints - before any sign-in filter runs - so no sign-in of any
 * kind, a client certificate included, can succeed first. The acknowledgment lives in the
 * session; signing out ends the session, so the banner is shown again before the next sign-in.
 *
 * <p>The text is read once, at startup: a file named in {@code ENS_CONSENT_BANNER_FILE} wins
 * over {@code ENS_CONSENT_BANNER_TEXT}, and with neither the standard DoD text is used. A file
 * that cannot be read stops the application starting rather than showing a banner that does
 * not say what it was meant to.
 */
@Component
public class ConsentBanner {

    /** The session attribute that records the acknowledgment. */
    public static final String ACKNOWLEDGED = ConsentBanner.class.getName() + ".acknowledged";

    public static final String DEFAULT_TITLE =
            "U.S. Government (USG) Information System (IS) Notice and Consent";

    /** The standard DoD notice and consent text, exactly. */
    public static final String DEFAULT_TEXT = """
            You are accessing a U.S. Government (USG) Information System (IS) that is provided \
            for USG-authorized use only. By using this IS (which includes any device attached to \
            this IS), you consent to the following conditions:

            - The USG routinely intercepts and monitors communications on this IS for purposes \
            including, but not limited to, penetration testing, COMSEC monitoring, network \
            operations and defense, personnel misconduct (PM), law enforcement (LE), and \
            counterintelligence (CI) investigations.
            - At any time, the USG may inspect and seize data stored on this IS.
            - Communications using, or data stored on, this IS are not private, are subject to \
            routine monitoring, interception, and search, and may be disclosed or used for any \
            USG-authorized purpose.
            - This IS includes security measures (e.g., authentication and access controls) to \
            protect USG interests--not for your personal benefit or privacy.
            - Notwithstanding the above, using this IS does not constitute consent to PM, LE or \
            CI investigative searching or monitoring of the content of privileged \
            communications, or work product, related to personal representation or services by \
            attorneys, psychotherapists, or clergy, and their assistants. Such communications \
            and work product are private and confidential. See User Agreement for details.
            """;

    /** One block of the text: a paragraph, or a list. */
    public record Block(String paragraph, List<String> items) {

        public boolean isList() {
            return items != null;
        }
    }

    private final boolean enabled;
    private final String title;
    private final List<Block> blocks;

    public ConsentBanner(EnsSecurityProperties properties) {
        EnsSecurityProperties.ConsentBanner settings = properties.getConsentBanner();
        this.enabled = settings.isEnabled();
        this.title = isBlank(settings.getTitle()) ? DEFAULT_TITLE : settings.getTitle().trim();
        this.blocks = parse(textOf(settings));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTitle() {
        return title;
    }

    public List<Block> getBlocks() {
        return blocks;
    }

    private static String textOf(EnsSecurityProperties.ConsentBanner settings) {
        if (!isBlank(settings.getFile())) {
            Path file = Path.of(settings.getFile().trim());
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!isBlank(text)) {
                    return text;
                }
                throw new IllegalStateException("ENS_CONSENT_BANNER_FILE " + file + " is empty");
            } catch (IOException e) {
                throw new IllegalStateException(
                        "ENS_CONSENT_BANNER_FILE " + file + " could not be read: " + e.getMessage(), e);
            }
        }
        return isBlank(settings.getText()) ? DEFAULT_TEXT : settings.getText();
    }

    /**
     * Plain text: blank lines separate paragraphs, and a paragraph that starts with "- " or
     * "* " is a list, one item per such line, with any line that does not start that way
     * continuing the item before it. Nothing is interpreted as markup - it is escaped when
     * shown.
     */
    static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        for (String chunk : text.replace("\r\n", "\n").split("\n\\s*\n")) {
            List<String> lines = chunk.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            if (isItem(lines.getFirst())) {
                List<String> items = new ArrayList<>();
                for (String line : lines) {
                    if (isItem(line)) {
                        items.add(line.substring(2).strip());
                    } else {
                        items.set(items.size() - 1, items.getLast() + " " + line);
                    }
                }
                blocks.add(new Block(null, List.copyOf(items)));
            } else {
                blocks.add(new Block(String.join(" ", lines), null));
            }
        }
        return List.copyOf(blocks);
    }

    private static boolean isItem(String line) {
        return line.startsWith("- ") || line.startsWith("* ");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
