/*
 * The metrics page.
 *
 * The chart is inline SVG drawn here rather than a charting library: everything this
 * application serves has to come out of the jar, and two series of twenty-five points do
 * not justify another dependency to ship to an air-gapped network.
 */
(function (window, $) {
    'use strict';

    var CHART_WIDTH = 960;
    var CHART_HEIGHT = 220;
    var PADDING_LEFT = 40;
    var PADDING_BOTTOM = 22;
    var PADDING_TOP = 8;

    function escape(value) {
        return CertAlert.escapeHtml(value);
    }

    /** A bar per month, two series side by side, with now marked between them. */
    function chart(months) {
        if (!months || months.length === 0) {
            return '<div class="text-secondary">Nothing cached yet.</div>';
        }
        var highest = months.reduce(function (max, point) {
            return Math.max(max, point.issued, point.expiring);
        }, 0) || 1;

        var plotWidth = CHART_WIDTH - PADDING_LEFT;
        var plotHeight = CHART_HEIGHT - PADDING_BOTTOM - PADDING_TOP;
        var slot = plotWidth / months.length;
        var barWidth = Math.max(2, Math.min(14, slot / 2 - 2));
        var thisMonth = new Date().toISOString().slice(0, 7);

        var bars = '';
        var labels = '';
        var marker = '';
        months.forEach(function (point, index) {
            var left = PADDING_LEFT + index * slot;
            var issuedHeight = (point.issued / highest) * plotHeight;
            var expiringHeight = (point.expiring / highest) * plotHeight;
            var base = PADDING_TOP + plotHeight;

            bars += '<rect x="' + (left + slot / 2 - barWidth - 1) + '" y="' + (base - issuedHeight)
                + '" width="' + barWidth + '" height="' + issuedHeight + '" class="metric-bar-issued">'
                + '<title>' + escape(point.month + ': ' + point.issued + ' issued') + '</title></rect>';
            bars += '<rect x="' + (left + slot / 2 + 1) + '" y="' + (base - expiringHeight)
                + '" width="' + barWidth + '" height="' + expiringHeight + '" class="metric-bar-expiring">'
                + '<title>' + escape(point.month + ': ' + point.expiring + ' expiring') + '</title></rect>';

            if (index % 3 === 0) {
                labels += '<text x="' + (left + slot / 2) + '" y="' + (CHART_HEIGHT - 6)
                    + '" text-anchor="middle" class="metric-axis">' + escape(point.month.slice(2)) + '</text>';
            }
            if (point.month === thisMonth) {
                marker = '<line x1="' + left + '" y1="' + PADDING_TOP + '" x2="' + left + '" y2="' + base
                    + '" class="metric-now"></line>'
                    + '<text x="' + (left + 4) + '" y="' + (PADDING_TOP + 10) + '" class="metric-axis">now</text>';
            }
        });

        // Three gridlines is enough to read a magnitude off without turning into a table.
        var grid = '';
        [0, 0.5, 1].forEach(function (fraction) {
            var y = PADDING_TOP + plotHeight - fraction * plotHeight;
            grid += '<line x1="' + PADDING_LEFT + '" y1="' + y + '" x2="' + CHART_WIDTH + '" y2="' + y
                + '" class="metric-grid"></line>'
                + '<text x="' + (PADDING_LEFT - 6) + '" y="' + (y + 3) + '" text-anchor="end" class="metric-axis">'
                + Math.round(fraction * highest) + '</text>';
        });

        return '<svg viewBox="0 0 ' + CHART_WIDTH + ' ' + CHART_HEIGHT + '" class="metric-chart" '
            + 'preserveAspectRatio="none" role="img" aria-label="Certificates issued and expiring by month">'
            + grid + bars + marker + labels + '</svg>';
    }

    /** A distribution as a row per value with a bar, which is what these all are. */
    function distribution(rows, labelOf, countOf, emptyMessage) {
        if (!rows || rows.length === 0) {
            return '<tr><td colspan="3" class="text-secondary">' + escape(emptyMessage) + '</td></tr>';
        }
        var highest = rows.reduce(function (max, row) {
            return Math.max(max, countOf(row));
        }, 0) || 1;
        return rows.map(function (row) {
            var count = countOf(row);
            var percent = Math.round((count / highest) * 100);
            return '<tr>'
                + '<td>' + escape(labelOf(row)) + '</td>'
                + '<td class="mono text-end">' + count + '</td>'
                + '<td class="w-50"><div class="progress progress-sm">'
                + '<div class="progress-bar" style="width: ' + percent + '%"></div></div></td>'
                + '</tr>';
        }).join('');
    }

    /**
     * One attribute's values under a heading. Several of these stack into one card,
     * because they are three answers to the same question - where in the organization is
     * this - and three cards would make them look like three subjects.
     */
    function group(heading, rows) {
        return '<tr><th colspan="3" class="text-secondary fw-normal pt-3">' + escape(heading) + '</th></tr>'
            + distribution(
                rows,
                function (row) { return row.name || 'Not stated'; },
                function (row) { return row.count; },
                'Nothing cached yet.');
    }

    /** The authorities' own words for why, where they gave one. */
    function reasons(rows) {
        if (!rows || rows.length === 0) {
            return '';
        }
        return ' ' + rows.map(function (row) {
            return escape(row.name) + ' ' + row.count;
        }).join(', ') + '.';
    }

    function stat(id, value) {
        $('[data-metric="' + id + '"]').text(value === undefined || value === null ? '—' : value);
    }

    $(function () {
        if ($('#metrics-page').length === 0) {
            return;
        }
        $.getJSON('/api/v1/metrics')
            .done(function (metrics) {
                var certificates = metrics.certificates || {};
                var entries = metrics.entries || {};
                var expiry = metrics.expiry || {};
                var notifications = metrics.notifications || {};
                var risks = metrics.risks || {};
                var issuance = metrics.issuance || {};
                var revocation = metrics.revocation || {};
                var byStatus = revocation.byStatus || {};
                var attributes = metrics.attributes || {};

                stat('certificates', certificates.total);
                stat('valid', certificates.VALID || 0);
                stat('expiring', certificates.EXPIRING_SOON || 0);
                stat('expired', certificates.EXPIRED || 0);
                stat('users', entries.users);
                stat('servers', entries.servers);
                stat('usersWithout', entries.usersWithoutCertificate);
                stat('serversWithout', entries.serversWithoutCertificate);
                stat('projects', metrics.projects);
                stat('next7', expiry.next7);
                stat('next30', expiry.next30);
                stat('next90', expiry.next90);
                stat('next365', expiry.next365);
                stat('raised', notifications.raised);
                stat('unread', notifications.unread);
                stat('emailed', notifications.emailed);
                stat('delivered', notifications.delivered);
                stat('failed', notifications.failed);
                stat('riskAny', risks.any);
                stat('riskWildcard', risks.WILDCARD);
                stat('riskBroadWildcard', risks.BROAD_WILDCARD);
                stat('riskManyNames', risks.MANY_NAMES);
                stat('riskManyDomains', risks.MANY_DOMAINS);
                stat('riskBareHostname', risks.BARE_HOSTNAME);
                stat('revoked', byStatus.REVOKED);
                stat('revocationGood', byStatus.GOOD);
                stat('revocationUnknown', byStatus.UNKNOWN);
                stat('revocationNotChecked', byStatus.NOT_CHECKED);
                stat('revokedLast30', revocation.revokedLast30Days);
                stat('revokedLast365', revocation.revokedLast365Days);
                // Null until something has been checked, which is not the same as zero.
                stat('revocationOldest', revocation.oldestCheck
                    ? String(revocation.oldestCheck).substring(0, 10) : null);
                $('[data-metric="revocationReasons"]').html(reasons(revocation.reasons));
                stat('issued30', issuance.last30Days);
                stat('issued90', issuance.last90Days);
                stat('issued365', issuance.last365Days);
                stat('averageValidity', issuance.averageValidityDays == null
                    ? null : issuance.averageValidityDays + ' days');

                $('#metrics-issuers').html(distribution(
                    issuance.issuers,
                    function (row) { return row.name; },
                    function (row) { return row.count; },
                    'No certificates cached yet.'));
                $('#metrics-user-attributes').html(
                    group('Duty organization', attributes.userDutyOrganizations)
                    + group('Duty sub-organization', attributes.userDutySubOrganizations)
                    + group('Employee type', attributes.userEmployeeTypes));
                $('#metrics-server-attributes').html(
                    group('Duty organization', attributes.serverDutyOrganizations)
                    + group('Duty sub-organization', attributes.serverDutySubOrganizations)
                    + group('Employee type', attributes.serverEmployeeTypes));

                $('#metrics-chart').html(chart(metrics.months));
                $('#metrics-keys').html(distribution(
                    metrics.keys,
                    function (row) { return row.label; },
                    function (row) { return row.count; },
                    'No certificates cached yet.'));
                $('#metrics-hashes').html(distribution(
                    metrics.hashes,
                    function (row) { return row.algorithm || 'not determined'; },
                    function (row) { return row.count; },
                    'No certificates cached yet.'));
                $('#metrics-signatures').html(distribution(
                    metrics.signatures,
                    function (row) { return row.algorithm || 'unknown'; },
                    function (row) { return row.count; },
                    'No certificates cached yet.'));
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $('#metrics-chart').html('<div class="text-secondary">Could not load the metrics.</div>');
            });
    });
})(window, jQuery);
