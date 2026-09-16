/* Triggers a directory sync from the page header and reports what it did. */
(function ($) {
    'use strict';

    $(function () {
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
