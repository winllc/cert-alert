/*
 * Asking a server what it is actually serving, from its page.
 *
 * The directory records what was issued; only the endpoint knows what was installed. The
 * answer is not kept anywhere - it is true at the moment it is asked and stops being true
 * the next time somebody restarts the service - so this draws it and forgets it.
 */
(function (window, $) {
    'use strict';

    var TONE = {
        INFO: 'bg-green-lt',
        WARNING: 'bg-yellow-lt',
        CRITICAL: 'bg-red-lt'
    };

    function escapeHtml(value) {
        return window.CertAlert.escapeHtml(value);
    }

    function row(label, value) {
        return '<tr><th>' + escapeHtml(label) + '</th><td>' + value + '</td></tr>';
    }

    function day(iso) {
        return iso ? escapeHtml(String(iso).substring(0, 10)) : '<span class="text-secondary">&mdash;</span>';
    }

    function findings(list) {
        if (!list || list.length === 0) {
            return '';
        }
        return '<div class="mb-3">' + list.map(function (finding) {
            return '<span class="badge me-1 ' + (TONE[finding.severity] || 'bg-secondary-lt') + '">'
                + escapeHtml(finding.label) + '</span>';
        }).join('') + '</div>';
    }

    /**
     * What the directory says it should be serving, shown only when that is not what came
     * back - otherwise it is the same certificate printed twice.
     */
    function expected(result) {
        var want = result.expected;
        if (!want || !result.presented || want.sha256Fingerprint === result.presented.sha256Fingerprint) {
            return '';
        }
        return '<div class="mt-3">'
            + '<div class="text-secondary mb-1">The directory’s most recently issued certificate</div>'
            + '<table>'
            + row('Serial', '<span class="mono">' + escapeHtml(want.serialNumber) + '</span>')
            + row('Issued', day(want.notBefore))
            + row('Expires', day(want.notAfter))
            + row('SHA-256', '<span class="mono">' + escapeHtml(want.sha256Fingerprint) + '</span>')
            + '</table></div>';
    }

    function presented(result) {
        var leaf = result.presented;
        var names = (leaf.subjectAlternativeNames || []).join(', ');
        return '<table>'
            + row('Subject', escapeHtml(leaf.subjectDn))
            + row('Issuer', escapeHtml(leaf.issuerDn))
            + row('Serial', '<span class="mono">' + escapeHtml(leaf.serialNumber) + '</span>')
            + row('Valid from', day(leaf.notBefore))
            + row('Valid to', window.CertAlert.expiryCell(leaf.notAfter))
            + row('Names', names ? escapeHtml(names) : '<span class="text-secondary">&mdash;</span>')
            + row('Key', escapeHtml(leaf.keyAlgorithm) + ', ' + escapeHtml(leaf.signatureAlgorithm))
            + row('SHA-256', '<span class="mono">' + escapeHtml(leaf.sha256Fingerprint) + '</span>')
            + row('Chain', escapeHtml(String((result.chain || []).length)) + ' certificate'
                + ((result.chain || []).length === 1 ? '' : 's') + ' sent')
            + '</table>';
    }

    function connection(result) {
        var parts = [escapeHtml(result.host) + ':' + result.port];
        if (result.protocol) {
            parts.push(escapeHtml(result.protocol));
        }
        if (result.cipherSuite) {
            parts.push(escapeHtml(result.cipherSuite));
        }
        if (result.elapsedMillis != null) {
            parts.push(result.elapsedMillis + ' ms');
        }
        return '<div class="text-secondary small mb-2">' + parts.join(' &middot; ') + '</div>';
    }

    function render($card, result) {
        var $result = $card.find('.probe-result');
        $card.find('.probe-idle').addClass('d-none');
        if (!result.reachable) {
            $result.html(connection(result)
                + '<div class="alert alert-danger mb-0">' + escapeHtml(result.error) + '</div>');
            return;
        }
        $result.html('<div class="cert-detail">' + connection(result) + findings(result.findings)
            + presented(result) + expected(result) + '</div>');
    }

    function probe($card) {
        var id = $card.data('server-id');
        var port = parseInt($card.find('.probe-port').val(), 10);
        var $button = $card.find('.probe-run');
        $button.prop('disabled', true);
        $card.find('.probe-result').html('<div class="text-secondary">Connecting…</div>');
        $card.find('.probe-idle').addClass('d-none');

        $.ajax({
            url: '/api/v1/servers/' + id + '/probe' + (port ? '?port=' + port : ''),
            method: 'POST'
        }).done(function (result) {
            render($card, result);
        }).fail(function (xhr) {
            if (window.CertAlert.handleUnauthorized(xhr)) {
                return;
            }
            // A problem detail carries the reason; anything else gets the status line.
            var detail = (xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title))
                || xhr.statusText;
            $card.find('.probe-result')
                .html('<div class="alert alert-danger mb-0">' + escapeHtml(detail) + '</div>');
        }).always(function () {
            $button.prop('disabled', false);
        });
    }

    $(function () {
        var $card = $('#endpoint-probe');
        if ($card.length === 0) {
            return;
        }
        $card.on('click', '.probe-run', function () {
            probe($card);
        });
        // Enter in the port box means the same as pressing the button.
        $card.on('keydown', '.probe-port', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                probe($card);
            }
        });
    });
})(window, jQuery);
