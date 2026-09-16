/* Triggers a directory sync from the app bar and reports what it did. */
(function ($) {
    'use strict';

    $(function () {
        var $button = $('#sync-now');
        var $status = $('#sync-status');
        if ($button.length === 0) {
            return;
        }

        $button.on('click', function () {
            $button.prop('disabled', true);
            $status.text('Syncing…');
            $.ajax({url: '/api/v1/sync', type: 'POST'})
                .done(function (results) {
                    $status.text(results.map(function (result) {
                        return result.job.toLowerCase() + ': ' + result.entriesSeen + ' seen, '
                            + result.certificatesCached + ' new cert(s), ' + result.errors + ' error(s)';
                    }).join(' | '));
                    $('table.directory').each(function () {
                        $(this).DataTable().ajax.reload(null, false);
                    });
                })
                .fail(function (xhr) {
                    if (xhr.status === 403) {
                        $status.text('Sync failed: this account is not an administrator');
                        return;
                    }
                    if (xhr.status === 401) {
                        window.location.href = '/login';
                        return;
                    }
                    var detail = xhr.responseJSON && xhr.responseJSON.detail
                        ? xhr.responseJSON.detail
                        : 'check the application log';
                    $status.text('Sync failed: ' + detail);
                })
                .always(function () {
                    $button.prop('disabled', false);
                });
        });
    });
})(jQuery);
