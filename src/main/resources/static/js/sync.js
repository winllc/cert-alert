/*
 * The page header controls: triggering a sweep, and showing whether the changelog
 * connector is following the directory.
 */
(function ($) {
    'use strict';

    /**
     * Shows how current the cache is. The connector is optional, so this stays hidden
     * unless it is switched on.
     */
    function showChangelogStatus() {
        var $badge = $('#changelog-status');
        if ($badge.length === 0) {
            return;
        }
        $.getJSON('/api/v1/changelog').done(function (status) {
            if (!status.enabled) {
                return;
            }
            // Null-valued fields are omitted from the JSON, so these arrive as undefined.
            var lag = status.lag;
            var tone = 'bg-green-lt';
            var label = 'Live';
            if (!status.running) {
                tone = 'bg-secondary-lt';
                label = 'Connector stopped';
            } else if (lag != null && lag > 0) {
                tone = 'bg-yellow-lt';
                label = lag + ' change' + (lag === 1 ? '' : 's') + ' behind';
            }
            var detail = status.lastChangeNumber == null ? '' : ' · change ' + status.lastChangeNumber;
            $badge.removeClass('d-none')
                .attr('class', 'badge ' + tone)
                .attr('title', 'Following the directory changelog' + detail)
                .text(label + detail);
        });
    }

    $(function () {
        showChangelogStatus();
        var $button = $('#sync-now');
        var $status = $('#sync-status');
        if ($button.length === 0) {
            return;
        }
        var original = $button.html();

        $button.on('click', function () {
            $button.prop('disabled', true)
                .html('<span class="spinner-border spinner-border-sm me-2" role="status"></span>Syncing…');
            $status.text('');

            $.ajax({url: '/api/v1/sync', type: 'POST'})
                .done(function (results) {
                    $status.html(results.map(function (result) {
                        var tone = result.errors > 0 ? 'bg-red-lt' : 'bg-green-lt';
                        return '<span class="badge ' + tone + ' ms-1">' + result.job.toLowerCase() + ': '
                            + result.entriesSeen + ' seen, ' + result.certificatesCached + ' new</span>';
                    }).join(''));
                    CertAlert.refreshAll();
                })
                .fail(function (xhr) {
                    // On a demo the same 403 means the demo refusing to be changed, and
                    // the visitor is an administrator - so the badge below would be a
                    // plain lie. The refusal has already said what it was.
                    if (xhr.status === 403 && CertAlert.isDemo()) {
                        $status.text('');
                        return;
                    }
                    if (xhr.status === 403) {
                        $status.html('<span class="badge bg-red-lt">not an administrator</span>');
                        return;
                    }
                    if (xhr.status === 401) {
                        window.location.href = '/login';
                        return;
                    }
                    var detail = xhr.responseJSON && xhr.responseJSON.detail
                        ? xhr.responseJSON.detail
                        : 'check the application log';
                    $status.html('<span class="badge bg-red-lt">sync failed: '
                        + CertAlert.escapeHtml(detail) + '</span>');
                })
                .always(function () {
                    $button.prop('disabled', false).html(original);
                });
        });
    });
})(jQuery);
