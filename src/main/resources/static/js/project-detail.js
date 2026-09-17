/*
 * One project: who and what is in it.
 *
 * Both pickers work the same way as the contact picker on a server - type, choose, and the
 * page reloads with the change applied. A project is small enough that a reload is simpler
 * and more obviously correct than patching the badges in place.
 */
(function (window, $) {
    'use strict';

    var SEARCH_DELAY = 250;

    function message(text) {
        $('#project-detail-message').text(text || '');
    }

    function change(url, method) {
        $.ajax({url: url, type: method})
            .done(function () {
                window.location.reload();
            })
            .fail(function (xhr) {
                if (CertAlert.handleUnauthorized(xhr)) {
                    return;
                }
                var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
                message(detail || 'That change could not be made.');
            });
    }

    /**
     * @param search  endpoint that takes ?q= and returns rows
     * @param label   what to show for a row
     * @param idOf    the row's id
     * @param path    where to POST to add it
     */
    function picker($input, $suggestions, projectId, search, label, subtitle, idOf, path) {
        var timer = null;

        function close() {
            $suggestions.removeClass('show').empty();
        }

        $input.on('input', function () {
            var term = $input.val().trim();
            message('');
            window.clearTimeout(timer);
            if (term.length < 2) {
                close();
                return;
            }
            timer = window.setTimeout(function () {
                $.getJSON(search, {q: term})
                    .done(function (rows) {
                        if (rows.length === 0) {
                            close();
                            return;
                        }
                        $suggestions.html(rows.map(function (row) {
                            var extra = subtitle(row);
                            return '<button type="button" class="dropdown-item project-suggestion"'
                                + ' data-id="' + CertAlert.escapeHtml(idOf(row)) + '">'
                                + CertAlert.escapeHtml(label(row))
                                + (extra ? '<span class="text-secondary d-block small">'
                                    + CertAlert.escapeHtml(extra) + '</span>' : '')
                                + '</button>';
                        }).join('')).addClass('show');

                        $suggestions.find('.project-suggestion').on('click', function () {
                            close();
                            change('/api/v1/projects/' + projectId + path + $(this).data('id'), 'POST');
                        });
                    })
                    .fail(close);
            }, SEARCH_DELAY);
        });

        $input.on('blur', function () {
            window.setTimeout(close, 150);
        });
    }

    $(function () {
        var page = document.getElementById('project-page');
        if (!page) {
            return;
        }
        var projectId = page.dataset.projectId;

        $('.project-remove-user').on('click', function () {
            change('/api/v1/projects/' + projectId + '/users/' + $(this).data('user-id'), 'DELETE');
        });
        $('.project-remove-server').on('click', function () {
            change('/api/v1/projects/' + projectId + '/servers/' + $(this).data('server-id'), 'DELETE');
        });

        picker(
            $('#project-user-input'), $('#project-user-suggestions'), projectId,
            '/api/v1/users/search',
            function (user) { return user.displayName || user.uid || user.email; },
            function (user) { return [user.email, user.dutyOrganization].filter(Boolean).join(' · '); },
            function (user) { return user.id; },
            '/users/');

        picker(
            $('#project-server-input'), $('#project-server-suggestions'), projectId,
            '/api/v1/servers/search',
            function (server) { return server.commonName; },
            function (server) { return [server.serverUrl, server.dutyOrganization].filter(Boolean).join(' · '); },
            function (server) { return server.id; },
            '/servers/');
    });
})(window, jQuery);
