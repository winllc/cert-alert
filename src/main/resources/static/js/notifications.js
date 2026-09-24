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

    /**
     * The number on the bell. The badge is hidden when there is nothing rather than showing
     * a zero; the bell itself is not touched, because it is the only way to the page and
     * hiding it on a failed count would take the page away with it.
     */
    function refreshCount() {
        return $.getJSON('/api/v1/notifications/unread-count')
            .done(function (result) {
                var count = result.count || 0;
                $('#notification-count').text(count).toggleClass('d-none', count === 0);
            })
            .fail(function () {
                // No answer is not a count of zero, but it is not a number to show either.
                $('#notification-count').addClass('d-none');
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

    /**
     * How far ahead the round-up looks.
     *
     * <p>Shown to everybody, because it is what decides whether somebody hears about a
     * certificate in time to do anything about it. Only an administrator gets the button.
     */
    function settingsStatus(settings) {
        var parts = [];
        if (settings.fromConfiguration) {
            parts.push('From the configuration (' + settings.configuredLeadDays + ' days); nobody has set it here.');
        } else if (settings.updatedBy) {
            parts.push('Set by ' + settings.updatedBy + ' on ' + moment(settings.updatedAt) + '.');
        } else {
            parts.push('Set on ' + moment(settings.updatedAt) + '.');
        }
        if (!settings.emailEnabled) {
            parts.push('Email sending is off, so the round-up appears here and goes nowhere.');
        }
        return parts.join(' ');
    }

    function showSettings(settings) {
        $('#notification-lead-days').val(settings.leadDays)
            .attr('min', settings.minimumLeadDays)
            .attr('max', settings.maximumLeadDays);
        $('#notification-lead-status').text(settingsStatus(settings)).removeClass('text-danger');
    }

    function loadSettings() {
        if ($('#notification-settings').length === 0) {
            return;
        }
        $.getJSON('/api/v1/notifications/settings')
            .done(showSettings)
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    CertAlert.handleUnauthorized(xhr);
                    return;
                }
                $('#notification-lead-status').text('Could not read how far ahead the round-up looks.')
                    .addClass('text-danger');
            });
    }

    function saveSettings() {
        var $button = $('#notification-lead-save').prop('disabled', true);
        var days = parseInt($('#notification-lead-days').val(), 10);

        $.ajax({
            url: '/api/v1/notifications/settings',
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify({leadDays: isNaN(days) ? null : days})
        })
            .done(function (settings) {
                showSettings(settings);
                $('#notification-lead-status').text('Saved. ' + settingsStatus(settings));
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    CertAlert.handleUnauthorized(xhr);
                    return;
                }
                // A 403 here is somebody who may read the setting and not change it, which
                // is not a lapsed session: say so rather than sending them to the login form.
                var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
                $('#notification-lead-status')
                    .text(xhr.status === 403 ? 'Only an administrator can change this.' : (detail || 'Could not save.'))
                    .addClass('text-danger');
            })
            .always(function () {
                $button.prop('disabled', false);
            });
    }

    /**
     * What a dry run built. The plain-text half is shown because it is the one somebody can
     * read at a glance and check the wording of; the HTML is what most clients will render,
     * so it is offered beside it rather than instead.
     */
    function showDryRun(result) {
        var $card = $('#dry-run-card');
        var $body = $('#dry-run-messages');

        if (!result.messages || result.messages.length === 0) {
            $card.removeClass('d-none');
            $('#dry-run-summary').text('nothing to send');
            // Why, rather than leaving somebody to guess which of half a dozen reasons it
            // was. The run works it out; this only has to show it.
            $body.html('<p class="text-secondary mb-0">'
                + CertAlert.escapeHtml(result.note || 'Nothing would be sent.')
                + '</p>');
            return;
        }

        $('#dry-run-summary').text(result.messages.length < result.emailsSent
            ? 'the first ' + result.messages.length + ' of ' + result.emailsSent
            : result.messages.length + ' message(s)');

        $body.html(result.messages.map(function (message, index) {
            var id = 'dry-run-body-' + index;
            return '<div class="mb-3 pb-3' + (index ? '' : '') + ' border-bottom">'
                + '<div class="d-flex flex-wrap align-items-baseline gap-2 mb-2">'
                + '<span class="badge bg-blue-lt">' + CertAlert.escapeHtml(message.to) + '</span>'
                + '<strong>' + CertAlert.escapeHtml(message.subject) + '</strong>'
                + '<button type="button" class="btn btn-sm ms-auto dry-run-toggle" data-target="' + id + '">'
                + 'Show message</button>'
                + '</div>'
                + '<pre class="d-none bg-light text-body border rounded p-3 mb-0 small" id="' + id + '">'
                + CertAlert.escapeHtml(message.text) + '</pre>'
                + '</div>';
        }).join(''));

        $body.find('.dry-run-toggle').on('click', function () {
            var $pre = $('#' + $(this).data('target')).toggleClass('d-none');
            $(this).text($pre.hasClass('d-none') ? 'Show message' : 'Hide message');
        });

        $card.removeClass('d-none');
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
        loadSettings();
        loadPage(0);

        $('#notification-lead-save').on('click', saveSettings);
        $('#notification-lead-days').on('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                saveSettings();
            }
        });

        $('#notifications-read-all').on('click', function () {
            post('/api/v1/notifications/read-all').done(function () {
                loadPage(0);
                refreshCount();
            });
        });

        $('#notifications-dry-run').on('click', function () {
            var $button = $(this).prop('disabled', true);
            $('#notifications-digest-status').text('Rehearsing…');
            post('/api/v1/notifications/digest?dryRun=true')
                .done(function (result) {
                    $('#notifications-digest-status').text(
                        result.certificates + ' expiring, ' + result.peopleTold + ' would be told, '
                        + result.emailsSent + ' email(s) would be sent'
                        + (result.emailsSent && !result.emailEnabled ? ' — once email is switched on' : ''));
                    showDryRun(result);
                })
                .fail(function (xhr) {
                    if (CertAlert.handleUnauthorized(xhr)) {
                        return;
                    }
                    $('#notifications-digest-status').text('The dry run failed; see the log.');
                })
                .always(function () {
                    $button.prop('disabled', false);
                });
        });

        $('#notifications-digest').on('click', function () {
            var $button = $(this).prop('disabled', true);
            $('#notifications-digest-status').text('Running…');
            post('/api/v1/notifications/digest')
                .done(function (result) {
                    $('#notifications-digest-status').text(
                        result.certificates + ' expiring, ' + result.peopleTold + ' told, '
                        + result.emailsSent + ' emailed'
                        + (result.note ? ' — ' + result.note : ''));
                    loadSettings();
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
