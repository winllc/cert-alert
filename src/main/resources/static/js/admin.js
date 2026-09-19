/*
 * The administration page: the audit trail across every entry.
 *
 * The same search table the details pages carry, with the subject left open rather than
 * fixed - so it needs a column saying what each record is about, and filters for narrowing
 * a directory's worth of history down to the question being asked.
 */
(function ($) {
    'use strict';

    var ACTION_BADGE = {
        ENTRY_DISCOVERED: 'bg-blue-lt',
        ENTRY_PRUNED: 'bg-secondary-lt',
        CERTIFICATE_CACHED: 'bg-green-lt',
        CERTIFICATE_REMOVED: 'bg-orange-lt',
        CERTIFICATE_STATUS_CHANGED: 'bg-yellow-lt',
        CONTACT_ADDED: 'bg-purple-lt',
        CONTACT_REMOVED: 'bg-purple-lt',
        ADDRESS_ADDED: 'bg-purple-lt',
        ADDRESS_REMOVED: 'bg-purple-lt',
        PROJECT_JOINED: 'bg-cyan-lt',
        PROJECT_LEFT: 'bg-cyan-lt',
        ALERT_SENT: 'bg-azure-lt',
        ALERT_FAILED: 'bg-red-lt'
    };

    function moment(iso) {
        if (!iso) {
            return '';
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? '' : parsed.toISOString().slice(0, 16).replace('T', ' ') + 'Z';
    }

    function day(iso) {
        return iso ? new Date(iso).toISOString().slice(0, 10) : null;
    }

    /** The counts above the table, and how far back the trail actually goes. */
    function loadSummary() {
        $.getJSON('/api/v1/audit/summary')
            .done(function (summary) {
                $('#audit-total').text(summary.total.toLocaleString());
                $('#audit-last-day').text(summary.lastDay.toLocaleString());
                $('#audit-last-week').text(summary.lastWeek.toLocaleString());
                $('#audit-actors').text(summary.actors.toLocaleString());
                $('#audit-span').text(summary.oldest
                    ? 'From ' + day(summary.oldest) + ' to ' + day(summary.newest)
                    : 'Nothing recorded yet');
            })
            .fail(function (xhr) {
                CertAlert.handleUnauthorized(xhr);
            });
    }

    $(function () {
        if ($('#audit-table').length === 0) {
            return;
        }
        loadSummary();

        var kind = document.getElementById('audit-kind');
        var subjectType = document.getElementById('audit-subject-type');
        var actor = document.getElementById('audit-actor');
        var from = document.getElementById('audit-from');
        var to = document.getElementById('audit-to');
        // The box under the About column: it searches a record's name and its DN at once,
        // which is two columns of the table and so not a column search.
        var subject = document.querySelector('#audit-table tfoot input[data-filter="subject"]');
        var applied = document.getElementById('applied-filters');

        function readFilters() {
            var filters = {};
            if (kind.value) {
                filters.action = kind.value;
            }
            if (subjectType.value) {
                filters.subjectType = subjectType.value;
            }
            if (actor.value.trim()) {
                filters.actor = actor.value.trim();
            }
            if (subject && subject.value.trim()) {
                filters.subject = subject.value.trim();
            }
            if (from.value) {
                filters.from = from.value;
            }
            if (to.value) {
                filters.to = to.value;
            }
            return filters;
        }

        CertAlert.initTable({
            selector: '#audit-table',
            endpoint: '/api/v1/datatables/audit',
            readFilters: readFilters,
            filterInputs: [kind, subjectType, actor, subject, from, to],
            // Newest first: the recent end is the one being looked at.
            order: [[0, 'desc']],
            emptyMessage: 'Nothing has been recorded yet',
            onFiltersApplied: function (filters) {
                applied.innerHTML = CertAlert.describeFilters(filters);
            },
            columns: [
                {data: 'occurredAt', name: 'occurredAt', searchable: false, className: 'mono audit-when',
                    render: function (value, type) {
                        return type === 'display' ? CertAlert.escapeHtml(moment(value)) : value;
                    }},
                {data: 'label', name: 'action', searchable: false, className: 'audit-action',
                    render: function (value, type, row) {
                        if (type !== 'display') { return value; }
                        return '<span class="badge ' + (ACTION_BADGE[row.action] || 'bg-secondary-lt') + '">'
                            + CertAlert.escapeHtml(value) + '</span>';
                    }},
                // What it happened to. A record outlives its entry, so the link appears only
                // while there is something to link to; the DN is what is left afterwards.
                {data: 'subjectName', name: 'subjectName', render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    var name = value || row.subjectDn;
                    var path = row.subjectType === 'SERVER' ? '/servers/' : '/users/';
                    var label = row.subjectId
                        ? '<a href="' + path + row.subjectId + '">' + CertAlert.escapeHtml(name) + '</a>'
                        : CertAlert.escapeHtml(name);
                    return label + '<div class="text-secondary small mono">'
                        + CertAlert.escapeHtml(row.subjectDn) + '</div>';
                }},
                {data: 'summary', name: 'summary', render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value) : value;
                }},
                {data: 'actor', name: 'actor', searchable: false, className: 'audit-actor',
                    render: function (value, type) {
                        return type === 'display' ? CertAlert.text(value) : value;
                    }},
                {data: 'channel', name: 'channel', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value, '—') : value;
                }},
                {data: 'target', name: 'target', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value, '—') : value;
                }}
            ]
        });
    });
})(jQuery);
