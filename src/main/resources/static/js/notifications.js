/*
 * Notifications: the bell in the navigation bar, and the page behind it.
 *
 * The bell is on every page, so it asks for a count and nothing else. The page asks for the
 * notifications themselves.
 */
(function (window, $) {
    'use strict';

    var SEVERITY_BADGE = {
        CRITICAL: 'bg-red-lt',
        WARNING: 'bg-yellow-lt',
        INFO: 'bg-blue-lt'
    };

    function post(url) {
        return $.ajax({url: url, type: 'POST'});
    }

    /** The count on the bell. Hidden entirely when there is nothing, rather than a zero. */
    function refreshCount() {
        return $.getJSON('/api/v1/notifications/unread-count')
            .done(function (result) {
                var count = result.count || 0;
                $('#notification-bell').toggleClass('d-none', false);
                $('#notification-count').text(count).toggleClass('d-none', count === 0);
            })
            .fail(function () {
                $('#notification-bell').addClass('d-none');
            });
    }

    function moment(iso) {
        if (!iso) {
            return '';
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? '' : parsed.toISOString().slice(0, 16).replace('T', ' ') + 'Z';
    }

    function row(notification) {
        var badge = '<span class="badge ' + (SEVERITY_BADGE[notification.severity] || 'bg-secondary-lt') + '">'
            + CertAlert.escapeHtml(notification.severity.toLowerCase()) + '</span>';
        var subject = notification.link
            ? '<a href="' + notification.link + '">'
                + CertAlert.escapeHtml(notification.subjectName || notification.subjectType) + '</a>'
            : CertAlert.text(notification.subjectName);
        var emailed = notification.emailedAt
            ? '<span class="text-secondary" title="Emailed ' + CertAlert.escapeHtml(moment(notification.emailedAt))
                + '">emailed</span>'
            : '';

        return '<tr class="' + (notification.unread ? 'notification-unread' : '') + '"'
            + ' data-notification-id="' + CertAlert.escapeHtml(notification.id) + '">'
            + '<td class="audit-when mono">' + CertAlert.escapeHtml(moment(notification.createdAt)) + '</td>'
            + '<td>' + badge + '</td>'
            + '<td>' + CertAlert.escapeHtml(notification.kindLabel) + '</td>'
            + '<td>' + CertAlert.escapeHtml(notification.message) + '</td>'
            + '<td>' + subject + '</td>'
            + '<td>' + emailed + '</td>'
            + '<td class="text-end">'
            + (notification.unread
                ? '<button type="button" class="btn btn-sm notification-read">Mark read</button>'
                : '<span class="text-secondary">read</span>')
            + '</td>'
            + '</tr>';
    }

    function loadPage(page) {
        var $body = $('#notifications-body');
        $.getJSON('/api/v1/notifications', {page: page, size: 20})
            .done(function (result) {
                if (result.totalElements === 0) {
                    $body.html('<tr><td colspan="7" class="text-secondary">'
                        + 'Nothing to report. Notifications about certificates you are the point of contact for '
                        + 'appear here.</td></tr>');
                } else {
                    $body.html(result.content.map(row).join(''));
                }
                $('#notifications-range').text(result.totalElements === 0
                    ? ''
                    : (result.page * result.size + 1) + '–' + (result.page * result.size + result.content.length)
                        + ' of ' + result.totalElements);
                $('#notifications-previous').prop('disabled', result.first)
                    .off('click').on('click', function () { loadPage(Math.max(result.page - 1, 0)); });
                $('#notifications-next').prop('disabled', result.last)
                    .off('click').on('click', function () { loadPage(result.page + 1); });

                $body.find('.notification-read').on('click', function () {
                    var id = $(this).closest('tr').data('notification-id');
                    post('/api/v1/notifications/' + id + '/read').done(function () {
                        loadPage(result.page);
                        refreshCount();
                    });
                });
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                $body.html('<tr><td colspan="7" class="text-secondary">Could not load notifications.</td></tr>');
            });
    }

    $(function () {
        refreshCount();

        if ($('#notifications-body').length === 0) {
            return;
        }
        loadPage(0);

        $('#notifications-read-all').on('click', function () {
            post('/api/v1/notifications/read-all').done(function () {
                loadPage(0);
                refreshCount();
            });
        });

        $('#notifications-digest').on('click', function () {
            var $button = $(this).prop('disabled', true);
            $('#notifications-digest-status').text('Running…');
            post('/api/v1/notifications/digest')
                .done(function (result) {
                    $('#notifications-digest-status').text(
                        result.certificates + ' expiring, ' + result.peopleTold + ' told, '
                        + result.emailsSent + ' emailed');
                    loadPage(0);
                    refreshCount();
                })
                .fail(function (xhr) {
                    if (CertAlert.handleUnauthorized(xhr)) {
                        return;
                    }
                    $('#notifications-digest-status').text('The round-up failed; see the log.');
                })
                .always(function () {
                    $button.prop('disabled', false);
                });
        });
    });

    window.CertAlertNotifications = {refreshCount: refreshCount};
})(window, jQuery);
