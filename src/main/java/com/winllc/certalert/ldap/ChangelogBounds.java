package com.winllc.certalert.ldap;

/**
 * The span of changes the directory still holds, as it advertises them.
 *
 * <p>A changelog is trimmed as it ages, so these move forward over time. Either may be
 * absent: not every directory publishes them.
 *
 * @param first lowest change number still retained
 * @param last highest change number recorded
 */
public record ChangelogBounds(Long first, Long last) {

    public static final ChangelogBounds UNKNOWN = new ChangelogBounds(null, null);
}
