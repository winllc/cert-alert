package com.winllc.certalert.ldap;

/**
 * One entry of the directory's changelog.
 *
 * <p>Deliberately carries only what identifies the change, not the modifications
 * themselves. See {@code DirectoryChangeApplier} for why.
 *
 * @param changeNumber monotonic position in the changelog
 * @param targetDn the entry that changed
 * @param changeType add, modify, delete or modrdn
 * @param newRdn for a modrdn, the new relative name
 * @param newSuperior for a modrdn that also moved the entry, its new parent
 */
public record ChangelogEntry(
        long changeNumber, String targetDn, ChangeType changeType, String newRdn, String newSuperior) {

    /** The kinds of change a changelog records. */
    public enum ChangeType {
        ADD,
        MODIFY,
        DELETE,
        MODRDN,
        /** Something this connector does not recognise; skipped rather than guessed at. */
        UNKNOWN;

        public static ChangeType parse(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "add" -> ADD;
                case "modify" -> MODIFY;
                case "delete" -> DELETE;
                // Directories spell this both ways.
                case "modrdn", "moddn" -> MODRDN;
                default -> UNKNOWN;
            };
        }
    }

    /** The DN the entry now lives at, which a modrdn moves. */
    public String effectiveDn() {
        if (changeType != ChangeType.MODRDN || newRdn == null || newRdn.isBlank()) {
            return targetDn;
        }
        if (newSuperior != null && !newSuperior.isBlank()) {
            return newRdn + "," + newSuperior;
        }
        int comma = targetDn.indexOf(',');
        return comma < 0 ? newRdn : newRdn + targetDn.substring(comma);
    }
}
