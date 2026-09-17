/*
 * An entry's history, at the bottom of its expanded row.
 *
 * Paged rather than scrolled: a server that has been watched for a year has a history
 * longer than anything worth putting in a table row, and the interesting end of it is the
 * recent end, which is where a page-at-a-time view starts.
 */
(function (window, $) {
    'use strict';

    var PAGE_SIZE = 10;

    /** Tabler's light-tint badges, by what kind of thing happened. */
    var ACTION_BADGE = {
        ENTRY_DISCOVERED: 'bg-secondary-lt',
        ENTRY_PRUNED: 'bg-red-lt',
        CERTIFICATE_CACHED: 'bg-green-lt',
        CERTIFICATE_REMOVED: 'bg-orange-lt',
        CERTIFICATE_STATUS_CHANGED: 'bg-yellow-lt',
        CONTACT_ADDED: 'bg-blue-lt',
        CONTACT_REMOVED: 'bg-azure-lt',
        ALERT_SENT: 'bg-purple-lt',
        ALERT_FAILED: 'bg-red-lt'
    };

    function url(kind, id, page) {
        return '/api/v1/' + kind + '/' + encodeURIComponent(id) + '/audit?page=' + page + '&size=' + PAGE_SIZE;
    }

    /** The placeholder that goes into the expanded row before the history has loaded. */
    function placeholder(kind) {
        return function (row) {
            return '<div class="audit" data-audit-kind="' + kind + '"'
                + ' data-audit-id="' + CertAlert.escapeHtml(row.id) + '" data-audit-page="0">'
                + '<div class="audit-heading">History</div>'
                + '<div class="text-secondary">Loading…</div></div>';
        };
    }

    /** A moment, to the minute: these are events, and the second is rarely the point. */
    function moment(iso) {
        if (!iso) {
            return '';
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? '' : parsed.toISOString().slice(0, 16).replace('T', ' ') + 'Z';
    }

    function eventRow(event) {
        var badge = '<span class="badge ' + (ACTION_BADGE[event.action] || 'bg-secondary-lt') + '">'
            + CertAlert.escapeHtml(event.label) + '</span>';
        var aside = [
            event.channel ? 'via ' + event.channel : null,
            event.target ? 'to ' + event.target : null
        ].filter(Boolean).join(', ');

        return '<tr>'
            + '<td class="audit-when mono">' + CertAlert.escapeHtml(moment(event.occurredAt)) + '</td>'
            + '<td class="audit-action">' + badge + '</td>'
            + '<td>' + CertAlert.escapeHtml(event.summary)
            + (aside ? ' <span class="text-secondary">(' + CertAlert.escapeHtml(aside) + ')</span>' : '')
            + '</td>'
            + '<td class="audit-actor">' + CertAlert.text(event.actor) + '</td>'
            + '</tr>';
    }

    function render($container, page) {
        var html = '<div class="audit-heading">History</div>';
        if (page.totalElements === 0) {
            $container.html(html + '<div class="text-secondary">Nothing has happened to this entry yet.</div>');
            return;
        }

        var first = page.page * page.size + 1;
        var last = first + page.content.length - 1;
        html += '<table class="audit-table"><tbody>' + page.content.map(eventRow).join('') + '</tbody></table>'
            + '<div class="audit-pager">'
            + '<button type="button" class="btn btn-sm audit-previous"' + (page.first ? ' disabled' : '') + '>'
            + 'Previous</button>'
            + '<button type="button" class="btn btn-sm audit-next"' + (page.last ? ' disabled' : '') + '>'
            + 'Next</button>'
            + '<span class="text-secondary">' + first + '–' + last + ' of ' + page.totalElements + '</span>'
            + '</div>';
        $container.html(html);
    }

    function load($container, page) {
        var kind = $container.data('audit-kind');
        var id = $container.data('audit-id');
        $.getJSON(url(kind, id, page))
            .done(function (result) {
                $container.data('audit-page', result.page);
                render($container, result);
                $container.find('.audit-previous').on('click', function () {
                    load($container, Math.max(result.page - 1, 0));
                });
                $container.find('.audit-next').on('click', function () {
                    load($container, result.page + 1);
                });
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $container.html('<div class="audit-heading">History</div>'
                    + '<div class="text-secondary">Could not load the history.</div>');
            });
    }

    window.AuditTrail = {
        placeholder: placeholder,
        attach: function ($childRow) {
            $childRow.find('.audit').each(function () {
                load($(this), 0);
            });
        }
    };
})(window, jQuery);
